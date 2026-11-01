//! Pre-prune source hashing for incremental Git backups.
//! Uses xxHash3-64 for fast change detection.

use rayon::prelude::*;
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{self, ErrorKind, Read};
use std::path::{Path, PathBuf};
use walkdir::WalkDir;
use xxhash_rust::xxh3::Xxh3;

use crate::world_copy;

const HASH_BUF_SIZE: usize = 1024 * 1024;

#[derive(Clone, Debug)]
pub struct FileFingerprint {
    pub hash: String,
    pub size: u64,
}

#[derive(Debug, Default)]
pub struct ScanResult {
    /// Relative path (slash-separated) -> fingerprint of live source bytes.
    pub files: HashMap<String, FileFingerprint>,
    pub changed: Vec<String>,
    pub deleted: Vec<String>,
    pub unchanged: Vec<String>,
}

/// Walks `world_root` (respecting blacklist), hashes each file with xxHash3-64,
/// and compares against `previous` fingerprints.
/// When `force_all` is true (prune policy changed), every file is marked changed.
pub fn scan_world_sources(
    world_root: &Path,
    blacklist: &[String],
    previous: &HashMap<String, FileFingerprint>,
    force_all: bool,
    max_worker_threads: usize,
) -> io::Result<ScanResult> {
    let plan = world_copy::build_hash_plan(world_root, blacklist)?;
    let threads = crate::pruner::resolve_pruner_threads(max_worker_threads);
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(threads)
        .build()
        .map_err(|e| io::Error::new(ErrorKind::Other, format!("rayon thread pool: {e}")))?;

    let hashed: Vec<(String, io::Result<FileFingerprint>)> = pool.install(|| {
        plan.par_iter()
            .map(|(abs, rel)| {
                let rel_key = rel.replace('\\', "/");
                (rel_key, hash_file(abs))
            })
            .collect()
    });

    let mut result = ScanResult::default();
    let mut seen = HashMap::new();

    for (rel, fp_result) in hashed {
        let fp = match fp_result {
            Ok(fp) => fp,
            Err(err) if err.kind() == ErrorKind::NotFound => continue,
            Err(err) => return Err(err),
        };
        let prev = previous.get(&rel);
        let changed = force_all
            || prev.map(|p| p.hash != fp.hash || p.size != fp.size).unwrap_or(true);
        if changed {
            result.changed.push(rel.clone());
        } else {
            result.unchanged.push(rel.clone());
        }
        seen.insert(rel.clone(), ());
        result.files.insert(rel, fp);
    }

    for key in previous.keys() {
        if !seen.contains_key(key) {
            result.deleted.push(key.clone());
        }
    }

    result.changed.sort();
    result.deleted.sort();
    result.unchanged.sort();
    Ok(result)
}

fn hash_file(path: &Path) -> io::Result<FileFingerprint> {
    let meta = fs::metadata(path)?;
    let size = meta.len();
    let mut file = File::open(path)?;
    let mut hasher = Xxh3::new();
    let mut buf = vec![0u8; HASH_BUF_SIZE];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(FileFingerprint {
        hash: format!("{:016x}", hasher.digest()),
        size,
    })
}

/// Serialize scan result as JSON for the Java bridge.
pub fn scan_result_to_json(result: &ScanResult, prune_policy_hash: &str) -> String {
    let mut out = String::with_capacity(256 + result.files.len() * 64);
    out.push_str("{\"version\":1,\"prunePolicyHash\":");
    push_json_string(&mut out, prune_policy_hash);
    out.push_str(",\"changed\":[");
    for (i, p) in result.changed.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        push_json_string(&mut out, p);
    }
    out.push_str("],\"deleted\":[");
    for (i, p) in result.deleted.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        push_json_string(&mut out, p);
    }
    out.push_str("],\"unchanged\":[");
    for (i, p) in result.unchanged.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        push_json_string(&mut out, p);
    }
    out.push_str("],\"files\":{");
    let mut first = true;
    let mut keys: Vec<&String> = result.files.keys().collect();
    keys.sort();
    for key in keys {
        let fp = &result.files[key];
        if !first {
            out.push(',');
        }
        first = false;
        push_json_string(&mut out, key);
        out.push_str(":{\"hash\":");
        push_json_string(&mut out, &fp.hash);
        out.push_str(",\"size\":");
        out.push_str(&fp.size.to_string());
        out.push('}');
    }
    out.push_str("}}");
    out
}

pub fn fingerprints_from_json_object(files_json: &str) -> HashMap<String, FileFingerprint> {
    // Minimal parser for {"path":{"hash":"...","size":N},...} produced by Java.
    // Prefer empty on parse failure rather than aborting the backup. 
    // Backup still completes, but slower, since we lose the efficiency gain from fingerprinting.
    let mut map = HashMap::new();
    let trimmed = files_json.trim();
    if trimmed.is_empty() || trimmed == "{}" {
        return map;
    }
    
    if let Ok(parsed) = parse_files_map(trimmed) {
        map = parsed;
    }

    map
}

fn parse_files_map(s: &str) -> Result<HashMap<String, FileFingerprint>, ()> {
    let mut map = HashMap::new();
    let bytes = s.as_bytes();
    if bytes.first() != Some(&b'{') || bytes.last() != Some(&b'}') {
        return Err(());
    }
    let inner = &s[1..s.len() - 1];
    if inner.trim().is_empty() {
        return Ok(map);
    }
    // Split top-level entries by tracking braces/quotes.
    let mut depth = 0i32;
    let mut in_str = false;
    let mut escape = false;
    let mut start = 0usize;
    let chars: Vec<char> = inner.chars().collect();
    let mut entries: Vec<String> = Vec::new();
    for (i, ch) in chars.iter().enumerate() {
        if escape {
            escape = false;
            continue;
        }
        match ch {
            '\\' if in_str => escape = true,
            '"' => in_str = !in_str,
            '{' if !in_str => depth += 1,
            '}' if !in_str => depth -= 1,
            ',' if !in_str && depth == 0 => {
                entries.push(chars[start..i].iter().collect());
                start = i + 1;
            }
            _ => {}
        }
    }
    entries.push(chars[start..].iter().collect());

    for entry in entries {
        let entry = entry.trim();
        if entry.is_empty() {
            continue;
        }
        let colon = find_top_colon(entry).ok_or(())?;
        let key = unquote(entry[..colon].trim())?;
        let value = entry[colon + 1..].trim();
        let hash = extract_string_field(value, "hash").ok_or(())?;
        let size = extract_u64_field(value, "size").ok_or(())?;
        map.insert(key, FileFingerprint { hash, size });
    }
    Ok(map)
}

fn find_top_colon(s: &str) -> Option<usize> {
    let mut in_str = false;
    let mut escape = false;
    for (i, ch) in s.char_indices() {
        if escape {
            escape = false;
            continue;
        }
        match ch {
            '\\' if in_str => escape = true,
            '"' => in_str = !in_str,
            ':' if !in_str => return Some(i),
            _ => {}
        }
    }
    None
}

fn unquote(s: &str) -> Result<String, ()> {
    let s = s.trim();
    if s.len() < 2 || !s.starts_with('"') || !s.ends_with('"') {
        return Err(());
    }
    let mut out = String::new();
    let mut escape = false;
    for ch in s[1..s.len() - 1].chars() {
        if escape {
            out.push(ch);
            escape = false;
        } else if ch == '\\' {
            escape = true;
        } else {
            out.push(ch);
        }
    }
    // A well-formed JSON string cannot end mid-escape (that would escape the closing quote).
    // Reject truncated input rather than dropping a dangling '\\'.
    if escape {
        return Err(());
    }
    Ok(out)
}

#[cfg(test)]
mod unquote_tests {
    use super::unquote;

    #[test]
    fn trailing_backslash_json_keeps_backslash() {
        // JSON text "\\" (quote, backslash, backslash, quote) decodes to one '\'.
        assert_eq!(unquote("\"\\\\\"").unwrap(), "\\");
    }

    #[test]
    fn path_ending_with_backslash() {
        assert_eq!(unquote("\"dir\\\\\"").unwrap(), "dir\\");
    }

    #[test]
    fn rejects_dangling_escape_before_close() {
        // quote + backslash + quote is not a closed JSON string content.
        assert!(unquote("\"\\\"").is_err());
    }
}

fn extract_string_field(obj: &str, field: &str) -> Option<String> {
    let needle = format!("\"{field}\"");
    let idx = obj.find(&needle)?;
    let after = &obj[idx + needle.len()..];
    let colon = after.find(':')?;
    let rest = after[colon + 1..].trim_start();
    unquote(take_json_string(rest)?).ok()
}

fn extract_u64_field(obj: &str, field: &str) -> Option<u64> {
    let needle = format!("\"{field}\"");
    let idx = obj.find(&needle)?;
    let after = &obj[idx + needle.len()..];
    let colon = after.find(':')?;
    let rest = after[colon + 1..].trim_start();
    let end = rest
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(rest.len());
    rest[..end].parse().ok()
}

fn take_json_string(s: &str) -> Option<&str> {
    if !s.starts_with('"') {
        return None;
    }
    let mut escape = false;
    for (i, ch) in s.char_indices().skip(1) {
        if escape {
            escape = false;
            continue;
        }
        if ch == '\\' {
            escape = true;
        } else if ch == '"' {
            return Some(&s[..=i]);
        }
    }
    None
}

fn push_json_string(out: &mut String, s: &str) {
    out.push('"');
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

#[allow(dead_code)]
pub fn collect_rel_paths(world_root: &Path, blacklist: &[String]) -> io::Result<Vec<PathBuf>> {
    let mut paths = Vec::new();
    let walker = WalkDir::new(world_root)
        .follow_links(false)
        .into_iter()
        .filter_entry(|e| world_copy::entry_allowed_for_hash(e, world_root, blacklist));
    for entry in walker {
        let entry = entry.map_err(|e| io::Error::new(ErrorKind::Other, e.to_string()))?;
        if entry.file_type().is_file() {
            paths.push(entry.path().to_path_buf());
        }
    }
    Ok(paths)
}
