use rayon::prelude::*;
use std::fs;
use std::io::{self, ErrorKind};
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, Instant};
use walkdir::DirEntry;
use walkdir::WalkDir;

const SESSION_LOCK_FILE_NAME: &str = "session.lock";
const NEOFORGE_ATOMIC_TMP_SUFFIX: &str = ".neoforge-tmp";
const FABRIC_ATOMIC_TMP_SUFFIX: &str = ".fabric-tmp";

// Processes the blacklist
fn is_copy_blacklisted(relative_to_world_root: &Path, patterns: &[String]) -> bool {
    if patterns.is_empty() {
        return false;
    }
    let rel_slash = relative_to_world_root.to_string_lossy().replace('\\', "/");
    if rel_slash.is_empty() || rel_slash == "." {
        return false;
    }
    for pattern in patterns {
        let p = pattern.trim();
        if p.is_empty() {
            continue;
        }
        let p_norm = p.replace('\\', "/");
        if p_norm.contains('/') {
            if rel_slash == p_norm || rel_slash.starts_with(&format!("{p_norm}/")) {
                return true;
            }
        } else if relative_to_world_root
            .file_name()
            .and_then(|n| n.to_str())
            .is_some_and(|n| n == p)
        {
            return true;
        }
    }
    false
}

fn should_include_entry(entry: &DirEntry, world_root: &Path, blacklist: &[String]) -> bool {
    let path = entry.path();
    let Ok(rel) = path.strip_prefix(world_root) else {
        return true;
    };
    let name = entry.file_name().to_string_lossy();
    // Excplicitly exclude the session lock file
    if name == SESSION_LOCK_FILE_NAME {
        return false;
    }
    if name.ends_with(NEOFORGE_ATOMIC_TMP_SUFFIX) || name.ends_with(FABRIC_ATOMIC_TMP_SUFFIX) {
        return false;
    }
    !is_copy_blacklisted(rel, blacklist)
}

fn assert_cache_outside_world(world_root: &Path, cache_root: &Path) -> io::Result<()> {
    let w = fs::canonicalize(world_root)?;
    let c = fs::canonicalize(cache_root)?;
    if c.starts_with(&w) {
        return Err(io::Error::new(
            ErrorKind::PermissionDenied,
            format!(
                "refusing backup: cache folder is inside the world directory (world={w:?} cache={c:?})"
            ),
        ));
    }
    Ok(())
}

/// Walks `world_root`, creates directories under `dest_root`, returns (src, dst) file copy jobs.
pub fn build_copy_plan(
    world_root: &Path,
    dest_root: &Path,
    blacklist: &[String],
) -> io::Result<Vec<(PathBuf, PathBuf)>> {
    assert_cache_outside_world(world_root, dest_root)?;

    let world_root = world_root.to_path_buf();
    let mut copy_jobs: Vec<(PathBuf, PathBuf)> = Vec::new();

    let walker = WalkDir::new(&world_root)
        .follow_links(false)
        .into_iter()
        .filter_entry(|e| should_include_entry(e, &world_root, blacklist));

    for entry in walker {
        let entry = entry.map_err(|e| io::Error::new(ErrorKind::Other, e.to_string()))?;
        let file_type = entry.file_type();
        let rel = entry
            .path()
            .strip_prefix(&world_root)
            .map_err(|_| io::Error::new(ErrorKind::InvalidInput, "walk path left world root"))?;

        if file_type.is_dir() {
            fs::create_dir_all(dest_root.join(rel))?;
        } else if file_type.is_file() {
            copy_jobs.push((entry.path().to_path_buf(), dest_root.join(rel)));
        }
    }

    Ok(copy_jobs)
}

/// Windows: ERROR_SHARING_VIOLATION / ERROR_LOCK_VIOLATION while Minecraft still holds region files.
#[cfg(windows)]
fn is_transient_windows_copy_lock(err: &io::Error) -> bool {
    matches!(err.raw_os_error(), Some(32) | Some(33))
}

#[cfg(not(windows))]
fn is_transient_windows_copy_lock(_err: &io::Error) -> bool {
    false
}

/// Fallback if `raw_os_error` is missing but the std message still contains the Win32 code.
#[cfg(windows)]
fn is_sharing_violation_message(err: &io::Error) -> bool {
    let s = err.to_string();
    s.contains("os error 32") || s.contains("os error 33")
}

#[cfg(not(windows))]
fn is_sharing_violation_message(_err: &io::Error) -> bool {
    false
}

#[inline]
fn is_retryable_sharing_error(err: &io::Error) -> bool {
    is_transient_windows_copy_lock(err) || is_sharing_violation_message(err)
}

fn copy_one_file_with_retry(src: &Path, dst: &Path) -> io::Result<()> {
    // Region files can stay exclusively opened briefly after "pause saves", wait longer than a few seconds for them to be closed.
    const RETRY_BUDGET: Duration = Duration::from_secs(90);
    let deadline = Instant::now() + RETRY_BUDGET;
    let mut attempt: u32 = 0;
    loop {
        match fs::copy(src, dst) {
            Ok(_) => return Ok(()),
            Err(e) if e.kind() == ErrorKind::NotFound => return Ok(()),
            Err(e) => {
                if !is_retryable_sharing_error(&e) || Instant::now() >= deadline {
                    return Err(e);
                }
                attempt = attempt.saturating_add(1);
                let shift = attempt.saturating_sub(1).min(8);
                let ms = (15u64 << shift).min(300);
                thread::sleep(Duration::from_millis(ms));
            }
        }
    }
}

/// Copies one chunk of files in parallel using a dedicated rayon pool.
pub fn parallel_copy_file_chunk(
    chunk: &[(PathBuf, PathBuf)],
    max_copy_worker_threads: usize,
) -> io::Result<()> {
    if chunk.is_empty() {
        return Ok(());
    }
    let threads = crate::pruner::resolve_pruner_threads(max_copy_worker_threads);
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(threads)
        .build()
        .map_err(|e| io::Error::new(ErrorKind::Other, format!("rayon thread pool: {e}")))?;

    pool.install(|| {
        chunk.par_iter().try_for_each(|(src, dst)| {
            if let Some(parent) = dst.parent() {
                fs::create_dir_all(parent)?;
            }
            copy_one_file_with_retry(src, dst)
        })
    })
}
