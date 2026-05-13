use flate2::write::DeflateEncoder;
use rawzip::time::UtcDateTime;
use rawzip::{CompressionMethod, ZipArchiveWriter};
use std::fs::{self, File};
use std::io::{self, BufWriter, Cursor, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

fn rawzip_to_io(err: rawzip::Error) -> io::Error {
    io::Error::new(io::ErrorKind::Other, err)
}

fn zip_entry_name(world_root: &Path, file: &Path) -> io::Result<String> {
    let rel = file.strip_prefix(world_root).map_err(|_| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!(
                "path not under snapshot root: root={} file={}",
                world_root.display(),
                file.display()
            ),
        )
    })?;
    let s = rel.to_string_lossy().replace('\\', "/");
    if s.is_empty() || s == "." {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "empty zip entry name",
        ));
    }
    Ok(s)
}

fn dos_nowish() -> UtcDateTime {
    UtcDateTime::from_unix(
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0),
    )
}

/// Serialized zip writer shared between prune workers and the remainder walk.
pub struct ZipStreamSink {
    world_root: PathBuf,
    writer: Mutex<Option<ZipArchiveWriter<BufWriter<File>>>>,
}

impl ZipStreamSink {
    pub fn create(world_root: PathBuf, zip_path: PathBuf) -> io::Result<Self> {
        let file = File::create(zip_path)?;
        let buf = BufWriter::new(file);
        let archive = ZipArchiveWriter::new(buf);
        Ok(Self {
            world_root,
            writer: Mutex::new(Some(archive)),
        })
    }

    /// Deflate `bytes` as a single zip entry at `abs_path` relative to the snapshot root.
    pub fn add_file_deflated(&self, abs_path: &Path, bytes: &[u8]) -> io::Result<()> {
        let name = zip_entry_name(&self.world_root, abs_path)?;
        let mut guard = self.writer.lock().map_err(|e| {
            io::Error::new(io::ErrorKind::Other, format!("zip mutex poisoned: {e}"))
        })?;
        let archive = guard
            .as_mut()
            .ok_or_else(|| io::Error::new(io::ErrorKind::Other, "zip archive already finished"))?;

        let (mut entry, config) = archive
            .new_file(name.as_str())
            .compression_method(CompressionMethod::Deflate)
            .last_modified(dos_nowish())
            .start()
            .map_err(rawzip_to_io)?;

        let encoder = DeflateEncoder::new(&mut entry, flate2::Compression::default());
        let mut writer = config.wrap(encoder);
        let mut cursor = Cursor::new(bytes);
        io::copy(&mut cursor, &mut writer)?;
        let (encoder, output) = writer.finish().map_err(rawzip_to_io)?;
        encoder.finish()?;
        entry.finish(output).map_err(rawzip_to_io)?;
        Ok(())
    }

    /// Deflate an on-disk file into the zip
    pub fn add_file_from_path(&self, abs_path: &Path) -> io::Result<()> {
        let name = zip_entry_name(&self.world_root, abs_path)?;
        let metadata = fs::metadata(abs_path)?;
        let modified = metadata
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| UtcDateTime::from_unix(d.as_secs() as i64))
            .unwrap_or_else(dos_nowish);

        let mut guard = self.writer.lock().map_err(|e| {
            io::Error::new(io::ErrorKind::Other, format!("zip mutex poisoned: {e}"))
        })?;
        let archive = guard
            .as_mut()
            .ok_or_else(|| io::Error::new(io::ErrorKind::Other, "zip archive already finished"))?;

        let builder = archive
            .new_file(name.as_str())
            .compression_method(CompressionMethod::Deflate)
            .last_modified(modified);

        let (mut entry, config) = builder.start().map_err(rawzip_to_io)?;

        let mut file = fs::File::open(abs_path)?;
        let encoder = DeflateEncoder::new(&mut entry, flate2::Compression::default());
        let mut writer = config.wrap(encoder);
        io::copy(&mut file, &mut writer)?;
        let (encoder, output) = writer.finish().map_err(rawzip_to_io)?;
        encoder.finish()?;
        entry.finish(output).map_err(rawzip_to_io)?;
        Ok(())
    }

    pub fn finish(&self) -> io::Result<()> {
        let mut guard = self.writer.lock().map_err(|e| {
            io::Error::new(io::ErrorKind::Other, format!("zip mutex poisoned: {e}"))
        })?;
        if let Some(archive) = guard.take() {
            let mut buf = archive.finish().map_err(rawzip_to_io)?;
            buf.flush()?;
        }
        Ok(())
    }
}

/// Adds every regular file still under `sink.world_root` to the zip
pub fn append_remaining_snapshot_files(
    sink: &ZipStreamSink,
    poll_abort: &mut impl FnMut() -> bool,
) -> io::Result<()> {
    let root = sink.world_root.clone();
    let mut seen: usize = 0;
    for entry in walkdir::WalkDir::new(&root).follow_links(false) {
        if seen.is_multiple_of(256) && (*poll_abort)() {
            return Err(io::Error::new(
                io::ErrorKind::Interrupted,
                "backup zip aborted",
            ));
        }
        let entry = entry.map_err(|e| io::Error::new(io::ErrorKind::Other, e))?;
        if !entry.file_type().is_file() {
            continue;
        }
        let path = entry.path().to_path_buf();
        sink.add_file_from_path(&path)?;
        seen = seen.saturating_add(1);
    }
    Ok(())
}
