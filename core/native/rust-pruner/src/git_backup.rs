//! Minimal Git backup store via gitoxide (`gix`).
//! We walk the worktree ourselves and write blobs/trees/commits/tags.
//! Native `git` is preferred from Java when available. This is the bundled fallback.

use gix::bstr::ByteSlice;
use gix::objs::{tree, Object, Tree};
use std::fs;
use std::io::{self, ErrorKind};
use std::path::Path;

const BRANCH: &str = "main";
const AUTHOR_NAME: &str = "Chronos Backups";
const AUTHOR_EMAIL: &str = "chronos@localhost";

pub fn init_repo(worktree: &Path) -> io::Result<()> {
    fs::create_dir_all(worktree)?;
    let git_dir = worktree.join(".git");
    if git_dir.exists() {
        tune_config(worktree)?;
        return Ok(());
    }
    gix::init(worktree).map_err(gix_err)?;
    // Point HEAD at main before first commit.
    let head = git_dir.join("HEAD");
    fs::write(&head, format!("ref: refs/heads/{BRANCH}\n"))?;
    fs::create_dir_all(git_dir.join("refs/heads"))?;
    fs::create_dir_all(git_dir.join("refs/tags"))?;
    tune_config(worktree)?;
    Ok(())
}

fn tune_config(worktree: &Path) -> io::Result<()> {
    let config_path = worktree.join(".git/config");
    let mut body = if config_path.exists() {
        fs::read_to_string(&config_path)?
    } else {
        String::new()
    };
    ensure_config_kv(&mut body, "core", "autocrlf", "false");
    ensure_config_kv(&mut body, "core", "bigFileThreshold", "1073741824");
    ensure_config_kv(&mut body, "pack", "window", "0");
    ensure_config_kv(&mut body, "user", "name", AUTHOR_NAME);
    ensure_config_kv(&mut body, "user", "email", AUTHOR_EMAIL);
    if let Some(parent) = config_path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(config_path, body)?;
    Ok(())
}

fn ensure_config_kv(body: &mut String, section: &str, key: &str, value: &str) {
    let header = format!("[{section}]");
    let line = format!("\t{key} = {value}");
    if let Some(sec_idx) = body.find(&header) {
        let after = &body[sec_idx + header.len()..];
        // Index of the '\n' before the next "[section]", not the '[' itself.
        // Inserting at '[' would glue the new line onto the next header.
        let next_sec = after.find("\n[").unwrap_or(after.len());
        let section_body = &after[..next_sec];
        if section_body.contains(&format!("{key} =")) || section_body.contains(&format!("{key}=")) {
            return;
        }
        // next_sec is relative to `after`. Absolute index is after's start + next_sec.
        let insert_at = sec_idx + header.len() + next_sec;
        body.insert_str(insert_at, &format!("\n{line}"));
    } else {
        if !body.is_empty() && !body.ends_with('\n') {
            body.push('\n');
        }
        body.push_str(&format!("{header}\n{line}\n"));
    }
}

#[cfg(test)]
mod ensure_config_kv_tests {
    use super::ensure_config_kv;

    #[test]
    fn inserts_before_next_section_without_gluing_header() {
        let mut body = String::from("[core]\n\trepositoryformatversion = 0\n[user]\n\tname = Someone\n");
        ensure_config_kv(&mut body, "core", "autocrlf", "false");
        assert_eq!(
            body,
            "[core]\n\trepositoryformatversion = 0\n\tautocrlf = false\n[user]\n\tname = Someone\n"
        );
    }

    #[test]
    fn skips_existing_key() {
        let mut body = String::from("[core]\n\tautocrlf = false\n[user]\n\tname = Someone\n");
        ensure_config_kv(&mut body, "core", "autocrlf", "true");
        assert_eq!(body, "[core]\n\tautocrlf = false\n[user]\n\tname = Someone\n");
    }
}

pub fn commit_and_tag(worktree: &Path, backup_id: &str, message: &str) -> io::Result<String> {
    init_repo(worktree)?;
    let repo = gix::open(worktree).map_err(gix_err)?;
    let tree_id = write_worktree_tree(&repo, worktree)?;
    let parent = repo.head_id().ok().map(|id| id.detach());
    let parents: Vec<_> = parent.into_iter().collect();
    let msg = if message.is_empty() {
        format!("Chronos backup {backup_id}")
    } else {
        message.to_string()
    };
    let commit_id = repo
        .commit(format!("refs/heads/{BRANCH}"), msg, tree_id, parents)
        .map_err(gix_err)?;
    let tag = tag_name_for_backup_id(backup_id);
    write_lightweight_tag(worktree, &tag, &commit_id.to_string())?;
    Ok(commit_id.to_string())
}

fn write_lightweight_tag(worktree: &Path, tag: &str, commit_sha: &str) -> io::Result<()> {
    let path = worktree.join(".git/refs/tags").join(tag);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, format!("{commit_sha}\n"))?;
    Ok(())
}

pub fn list_backup_tags(worktree: &Path) -> io::Result<Vec<String>> {
    init_repo(worktree)?;
    let tags_dir = worktree.join(".git/refs/tags");
    if !tags_dir.is_dir() {
        return Ok(Vec::new());
    }
    let mut tags = Vec::new();
    for entry in fs::read_dir(tags_dir)? {
        let entry = entry?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if entry.path().is_file() {
            tags.push(name);
        }
    }
    tags.sort_by(|a, b| b.cmp(a));
    Ok(tags)
}

pub fn restore_tag(worktree: &Path, tag_name: &str, dest: &Path) -> io::Result<()> {
    let tag = normalize_tag(tag_name)?;
    init_repo(worktree)?;
    if dest.exists() {
        return Err(io::Error::new(
            ErrorKind::AlreadyExists,
            format!("Export destination already exists: {}", dest.display()),
        ));
    }
    fs::create_dir_all(dest)?;
    let repo = gix::open(worktree).map_err(gix_err)?;
    let tag_ref = format!("refs/tags/{tag}");
    let id = repo
        .find_reference(tag_ref.as_str())
        .map_err(gix_err)?
        .into_fully_peeled_id()
        .map_err(gix_err)?
        .object()
        .map_err(gix_err)?;
    let commit = id.peel_to_commit().map_err(gix_err)?;
    let tree = commit.tree().map_err(gix_err)?;
    checkout_tree(&repo, &tree, dest)?;
    Ok(())
}

fn checkout_tree(repo: &gix::Repository, tree: &gix::Tree<'_>, dest: &Path) -> io::Result<()> {
    for entry in tree.iter() {
        let entry = entry.map_err(gix_err)?;
        let name = entry.filename().to_str().map_err(|_| {
            io::Error::new(ErrorKind::InvalidData, "non-utf8 path in git tree")
        })?;
        let out = dest.join(name);
        let mode = entry.mode();
        let oid = entry.oid();
        if mode.is_tree() {
            fs::create_dir_all(&out)?;
            let obj = repo.find_object(oid).map_err(gix_err)?;
            let subtree = obj.try_into_tree().map_err(gix_err)?;
            checkout_tree(repo, &subtree, &out)?;
        } else if mode.is_blob() || mode.is_executable() || mode.is_link() {
            if let Some(parent) = out.parent() {
                fs::create_dir_all(parent)?;
            }
            let obj = repo.find_object(oid).map_err(gix_err)?;
            let blob = obj.try_into_blob().map_err(gix_err)?;
            // Symlink targets are written as plain files for restore portability.
            fs::write(&out, &blob.data)?;
            #[cfg(unix)]
            if mode.is_executable() {
                use std::os::unix::fs::PermissionsExt;
                let mut perms = fs::metadata(&out)?.permissions();
                perms.set_mode(0o755);
                fs::set_permissions(&out, perms)?;
            }
        }
    }
    Ok(())
}

fn write_worktree_tree(repo: &gix::Repository, dir: &Path) -> io::Result<gix::ObjectId> {
    let mut entries: Vec<tree::Entry> = Vec::new();
    let mut children: Vec<fs::DirEntry> = Vec::new();
    for entry in fs::read_dir(dir)? {
        children.push(entry?);
    }
    children.sort_by_key(|e| e.file_name());

    for child in children {
        let name = child.file_name();
        let name_str = name.to_string_lossy();
        if name_str == ".git" {
            continue;
        }
        let path = child.path();
        let file_name = gix::bstr::BString::from(name_str.as_bytes());
        if path.is_dir() {
            let oid = write_worktree_tree(repo, &path)?;
            entries.push(tree::Entry {
                mode: tree::EntryKind::Tree.into(),
                filename: file_name,
                oid,
            });
        } else if path.is_file() {
            let bytes = fs::read(&path)?;
            let oid = repo.write_blob(&bytes).map_err(gix_err)?.detach();
            let executable = is_executable(&path);
            let kind = if executable {
                tree::EntryKind::BlobExecutable
            } else {
                tree::EntryKind::Blob
            };
            entries.push(tree::Entry {
                mode: kind.into(),
                filename: file_name,
                oid,
            });
        }
    }

    // Git sorts tree entries by name, with trees as name + '/'
    entries.sort_by(|a, b| {
        let mut an = a.filename.to_vec();
        let mut bn = b.filename.to_vec();
        if a.mode.is_tree() {
            an.push(b'/');
        }
        if b.mode.is_tree() {
            bn.push(b'/');
        }
        an.cmp(&bn)
    });

    let tree = Tree { entries };
    Ok(repo.write_object(Object::Tree(tree)).map_err(gix_err)?.detach())
}

fn is_executable(path: &Path) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::metadata(path)
            .map(|m| m.permissions().mode() & 0o111 != 0)
            .unwrap_or(false)
    }
    #[cfg(not(unix))]
    {
        let _ = path;
        false
    }
}

fn normalize_tag(tag_name: &str) -> io::Result<String> {
    // Must match tag_name_for_backup_id so restore finds tags created via commit_and_tag.
    let tag = tag_name_for_backup_id(tag_name);
    if tag.is_empty() {
        return Err(io::Error::new(ErrorKind::InvalidInput, "Backup tag is empty"));
    }
    Ok(tag)
}

pub fn tag_name_for_backup_id(backup_id: &str) -> String {
    backup_id.trim().replace(' ', "_")
}

#[cfg(test)]
mod tag_name_tests {
    use super::{normalize_tag, tag_name_for_backup_id};

    #[test]
    fn normalize_tag_matches_commit_tag_with_spaces() {
        let backup_id = "backup id";
        let created = tag_name_for_backup_id(backup_id);
        let looked_up = normalize_tag(backup_id).unwrap();
        assert_eq!(created, "backup_id");
        assert_eq!(looked_up, created);
    }

    #[test]
    fn normalize_tag_idempotent_for_underscored_names() {
        assert_eq!(normalize_tag("backup_id").unwrap(), "backup_id");
    }

    #[test]
    fn normalize_tag_rejects_empty() {
        assert!(normalize_tag("   ").is_err());
    }
}

fn gix_err<E: std::fmt::Display>(err: E) -> io::Error {
    io::Error::new(ErrorKind::Other, format!("gitoxide: {err}"))
}
