use std::path::{Path, PathBuf};
use std::time::Instant;

use crate::world_copy;

const DEFAULT_WORLD_NAME: &str = "world";
const DEFAULT_PRUNE_SECONDS: u64 = 120;

fn test_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("test")
}

fn resolve_world_path(world_name: &str) -> PathBuf {
    test_root().join(world_name)
}

fn remove_dir_all_quiet(path: &Path) {
    let _ = std::fs::remove_dir_all(path);
}

struct TempSnapshot {
    path: PathBuf,
}

impl TempSnapshot {
    fn new(world_name: &str) -> Self {
        let path = test_root()
            .join(".prune-test-tmp")
            .join(format!("{world_name}-{}", std::process::id()));
        remove_dir_all_quiet(&path);
        Self { path }
    }
}

impl Drop for TempSnapshot {
    fn drop(&mut self) {
        remove_dir_all_quiet(&self.path);
    }
}

fn copy_world_snapshot(source: &Path, dest: &Path) -> Result<(), std::io::Error> {
    let copy_jobs = world_copy::build_copy_plan(source, dest, &[])?;
    for chunk in copy_jobs.chunks(2048) {
        world_copy::parallel_copy_file_chunk(chunk, 0)?;
    }
    Ok(())
}

/// Copies a test world, prunes the snapshot, prints elapsed prune time, then discards it.
pub fn run(world_name: Option<&str>) -> i32 {
    let world_name = world_name.unwrap_or(DEFAULT_WORLD_NAME);
    let source = resolve_world_path(world_name);

    if !source.join("level.dat").is_file() {
        eprintln!(
            "Warning: no Minecraft world found at {} (expected level.dat)",
            source.display()
        );
        return 1;
    }

    let snapshot = TempSnapshot::new(world_name);
    if let Err(err) = std::fs::create_dir_all(&snapshot.path) {
        eprintln!("Error: failed to create snapshot directory: {err}");
        return 1;
    }

    if let Err(err) = copy_world_snapshot(&source, &snapshot.path) {
        eprintln!("Error: failed to copy world snapshot: {err}");
        return 1;
    }

    let started = Instant::now();
    if let Err(err) = crate::prune_world_folder(
        snapshot.path.clone(),
        DEFAULT_PRUNE_SECONDS,
        0,
    ) {
        eprintln!("Error: failed to prune world snapshot: {err}");
        return 1;
    }
    let elapsed = started.elapsed();

    println!("Time to prune the world snapshot: {:.6} seconds", elapsed.as_secs_f64());
    0
}
