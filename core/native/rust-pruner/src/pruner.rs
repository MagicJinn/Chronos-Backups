use mca::{Compression, RegionReader, RegionWriter};
use mca::write::{PendingData, WritableChunk};
use rayon::prelude::*;
use std::fmt::{Display, Formatter};
use std::fs::{self, read};
use std::io::{BufWriter, Error, ErrorKind, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use crate::snapshot_zip::ZipStreamSink;
use na_nbt::{CompoundRef, ValueRef};

// Worlds have a meaningfully different structure before and after 26.1 snapshot 6
// World structure has changed over the years, but it's never been significantly different, so we could always work around it in those versions
const DATA_VERSION_WORLD_LAYOUT_26_1_SNAPSHOT_6: u32 = 4774;
const DIMENSIONS_FOLDER_NAME: &str = "dimensions";
const REGION_FOLDER_NAME: &str = "region";
const ENTITIES_FOLDER_NAME: &str = "entities";
const POI_FOLDER_NAME: &str = "poi";

const INHABITED_TIME_TAG_NAME: &str = "InhabitedTime";
const MIN_ANVIL_REGION_FILE_BYTES: u64 = 8192;
const REGION_SLOT_COUNT: usize = 32 * 32;

type KeptChunk = (u8, u8, Vec<u8>, Compression);

pub struct DataFolder {
    pub region_directory: PathBuf,
    pub entities_directory: PathBuf,
    pub poi_directory: PathBuf,
}
// Display trait
impl Display for DataFolder {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "DataFolder\n\tregion_directory: {}\n\tentities_directory: {}\n\tpoi_directory: {}",
            self.region_directory.display(),
            self.entities_directory.display(),
            self.poi_directory.display()
        )
    }
}

pub fn prune_world(
    world_folder: PathBuf,
    data_version: u32,
    inhabited_time_seconds_required: u64,
    max_worker_threads: usize,
    zip_sink: Option<Arc<ZipStreamSink>>,
) -> Result<(), std::io::Error> {
    if !world_folder.is_dir() {
        return Ok(());
    }

    if inhabited_time_seconds_required == 0 {
        println!("Pruned 0 chunks");
        return Ok(());
    }
    let inhabited_time_ticks_required = inhabited_time_seconds_required * 20;

    let data_folders = get_data_folders(&world_folder, data_version)?;

    let worker_threads = resolve_pruner_threads(max_worker_threads);
    println!(
        "Using {} worker thread{} for pruning",
        worker_threads,
        if worker_threads == 1 { "" } else { "s" }
    );

    let mut region_jobs: Vec<(PathBuf, Arc<Path>, Arc<Path>)> = Vec::new();

    // Build a job list once, then process each region file in parallel.
    for data_folder in data_folders {
        if !data_folder.region_directory.is_dir() {
            eprintln!(
                "Warning: region directory not found for data folder {}",
                data_folder.region_directory.display()
            );
            continue;
        }
        let entities_dir: Arc<Path> = data_folder.entities_directory.into();
        let poi_dir: Arc<Path> = data_folder.poi_directory.into();
        let region_files = data_folder.region_directory.read_dir()?;
        for region_file in region_files {
            let region_file = match region_file {
                Ok(entry) => entry,
                Err(err) => {
                    eprintln!("Warning: failed to read region file entry: {err}");
                    continue;
                }
            };

            let region_path = region_file.path();

            // Only process non-empty .mca files.
            let is_mca = region_path.extension().and_then(|ext| ext.to_str()) == Some("mca");
            if !is_mca {
                continue;
            }

            if parse_region_coords(&region_path).is_err() {
                eprintln!(
                    "Warning: skipping region file with unexpected name: {}",
                    region_path.display()
                );
                continue;
            }

            if !has_minimum_anvil_header(&region_path) {
                continue;
            }

            region_jobs.push((
                region_path,
                Arc::clone(&entities_dir),
                Arc::clone(&poi_dir),
            ));
        }
    }

    let thread_pool = rayon::ThreadPoolBuilder::new()
        .num_threads(worker_threads)
        .build()
        .map_err(|e| {
            Error::new(
                ErrorKind::Other,
                format!("failed to build thread pool: {e}"),
            )
        })?;

    let per_region_results = thread_pool.install(|| {
        region_jobs
            .par_iter()
            .map(|(region_path, entities_dir, poi_dir)| {
                process_region_file(
                    region_path.as_path(),
                    entities_dir.as_ref(),
                    poi_dir.as_ref(),
                    inhabited_time_ticks_required,
                    zip_sink.as_ref(),
                )
            })
            .collect::<Vec<Result<usize, std::io::Error>>>()
    });

    let mut pruned_chunks: usize = 0;
    for result in per_region_results {
        match result {
            Ok(pruned_in_region) => pruned_chunks += pruned_in_region,
            Err(err) => return Err(err),
        }
    }
    println!("Pruned {} chunks", pruned_chunks);

    // If we get here, we successfully pruned the world
    return Ok(());
}

fn process_region_file(
    region_path: &Path,
    entities_dir: &Path,
    poi_dir: &Path,
    inhabited_time_ticks_required: u64,
    zip_sink: Option<&Arc<ZipStreamSink>>,
) -> Result<usize, std::io::Error> {
    let region_bytes = match read(region_path) {
        Ok(bytes) => bytes,
        Err(err) => {
            eprintln!(
                "Warning: failed to read region file {}, skipping: {}",
                region_path.display(),
                err
            );
            return Ok(0);
        }
    };
    let mut region_reader = match RegionReader::new(&region_bytes) {
        Ok(reader) => reader,
        Err(err) => {
            eprintln!(
                "Warning: corrupt region file {}, skipping: {}",
                region_path.display(),
                err
            );
            return Ok(0);
        }
    };
    let generated = match region_reader.generated_chunks() {
        Ok(chunks) => chunks,
        Err(err) => {
            eprintln!(
                "Warning: failed to read generated chunks for {}, skipping: {}",
                region_path.display(),
                err
            );
            return Ok(0);
        }
    };

    // Track slots with a fixed-size bitmap (1024 slots per MCA)
    // to avoid HashSet hashing/allocation overhead.
    let mut slots_to_clear = [false; REGION_SLOT_COUNT];
    let mut pruned_in_region: usize = 0;
    let mut kept_chunks: Vec<KeptChunk> = Vec::with_capacity(generated.len());

    // Single pass: decide prune vs keep while chunk payload is still hot in cache.
    for &(x, z) in &generated {
        let compressed = match region_reader.chunk_data(x, z) {
            Ok(Some(chunk)) => chunk,
            Ok(None) => continue,
            Err(err) => {
                eprintln!(
                    "Warning: failed to read chunk ({}, {}) in {}, skipping chunk: {}",
                    x,
                    z,
                    region_path.display(),
                    err
                );
                continue;
            }
        };

        let payload = compressed.data.as_ref().to_vec();
        let compression = compressed.compression.clone();
        let decompressed = match region_reader.decompress_to_internal_buffer(compressed) {
            Ok(bytes) => bytes,
            Err(err) => {
                eprintln!(
                    "Warning: failed to decompress chunk ({}, {}) in {}, skipping chunk: {}",
                    x,
                    z,
                    region_path.display(),
                    err
                );
                continue;
            }
        };

        let inhabited_time = read_inhabited_time_ticks(decompressed);
        if inhabited_time < inhabited_time_ticks_required {
            if mark_slot(&mut slots_to_clear, x, z) {
                pruned_in_region += 1;
            }
        } else {
            kept_chunks.push((x, z, payload, compression));
        }
    }

    if pruned_in_region == 0 {
        return Ok(0);
    }

    write_mca_or_delete(region_path, kept_chunks, zip_sink)?;

    if let Some(region_file_name) = region_path.file_name() {
        rayon::join(
            || {
                clear_matching_slots_in_sibling_mca(
                    entities_dir,
                    region_file_name,
                    &slots_to_clear,
                    zip_sink,
                );
            },
            || {
                clear_matching_slots_in_sibling_mca(
                    poi_dir,
                    region_file_name,
                    &slots_to_clear,
                    zip_sink,
                );
            },
        );
    }

    Ok(pruned_in_region)
}

pub(crate) fn resolve_pruner_threads(configured_max_worker_threads: usize) -> usize {
    if configured_max_worker_threads > 0 {
        return configured_max_worker_threads.max(1);
    }

    if let Ok(raw) = std::env::var("RUST_PRUNER_THREADS") {
        if let Ok(parsed) = raw.trim().parse::<usize>() {
            return parsed.max(1);
        }
        eprintln!(
            "Warning: invalid RUST_PRUNER_THREADS='{}', falling back to default",
            raw
        );
    }

    let available = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(2);
    // Be a good desktop citizen, use half the available cores
    return (available / 2).max(1);
}

fn clear_matching_slots_in_sibling_mca(
    sibling_dir: &Path,
    region_file_name: &std::ffi::OsStr,
    slots_to_clear: &[bool; REGION_SLOT_COUNT],
    zip_sink: Option<&Arc<ZipStreamSink>>,
) {
    if !sibling_dir.is_dir() {
        return;
    }

    let path = sibling_dir.join(region_file_name);
    if !path.is_file() || !has_minimum_anvil_header(&path) {
        return;
    }

    let file = match read(&path) {
        Ok(bytes) => bytes,
        Err(_) => {
            return;
        }
    };

    let reader = match RegionReader::new(&file) {
        Ok(reader) => reader,
        Err(_) => {
            return;
        }
    };

    let generated = match reader.generated_chunks() {
        Ok(chunks) => chunks,
        Err(_) => {
            return;
        }
    };

    let mut removed_any = false;
    for &(x, z) in &generated {
        if is_slot_marked(slots_to_clear, x, z) {
            removed_any = true;
            break;
        }
    }

    if !removed_any {
        return;
    }

    let mut kept_chunks: Vec<KeptChunk> = Vec::with_capacity(generated.len());
    for &(x, z) in &generated {
        if is_slot_marked(slots_to_clear, x, z) {
            continue;
        }

        let compressed = match reader.chunk_data(x, z) {
            Ok(Some(chunk)) => chunk,
            Ok(None) => continue,
            Err(_) => continue,
        };

        kept_chunks.push((
            x,
            z,
            compressed.data.as_ref().to_vec(),
            compressed.compression.clone(),
        ));
    }

    let _ = write_mca_or_delete(&path, kept_chunks, zip_sink);
}

fn has_minimum_anvil_header(path: &Path) -> bool {
    match path.metadata() {
        Ok(meta) => meta.len() >= MIN_ANVIL_REGION_FILE_BYTES,
        Err(err) => {
            eprintln!(
                "Warning: could not stat MCA file {}, skipping: {}",
                path.display(),
                err
            );
            false
        }
    }
}

fn write_mca_or_delete(
    path: &Path,
    chunks: Vec<KeptChunk>,
    zip_sink: Option<&Arc<ZipStreamSink>>,
) -> Result<(), std::io::Error> {
    if chunks.is_empty() {
        if let Err(err) = fs::remove_file(path) {
            if err.kind() != ErrorKind::NotFound {
                return Err(err);
            }
        }
        return Ok(());
    }

    let mut writer = RegionWriter::new();
    for (x, z, data, compression) in chunks {
        *writer.chunk_mut(x, z).map_err(|e| {
            Error::new(ErrorKind::InvalidData, e.to_string())
        })? = Some(WritableChunk {
            data: PendingData::new_compressed(data, compression),
            chunk: (x, z),
            timestamp: None,
        });
    }

    if let Some(sink) = zip_sink {
        let mut buf = Vec::new();
        writer
            .write(&mut buf)
            .map_err(|e| Error::new(ErrorKind::InvalidData, e.to_string()))?;
        sink.add_file_deflated(path, &buf)?;
        if let Err(err) = fs::remove_file(path) {
            if err.kind() != ErrorKind::NotFound {
                return Err(err);
            }
        }
        return Ok(());
    }

    let mut out = BufWriter::new(fs::File::create(path)?);
    writer
        .write(&mut out)
        .map_err(|e| Error::new(ErrorKind::InvalidData, e.to_string()))?;
    out.flush()?;
    Ok(())
}

fn slot_index(x: u8, z: u8) -> u16 {
    (u16::from(z) << 5) | u16::from(x)
}

fn mark_slot(slots: &mut [bool; REGION_SLOT_COUNT], x: u8, z: u8) -> bool {
    let idx = usize::from(slot_index(x, z));
    let was_set = slots[idx];
    slots[idx] = true;
    !was_set
}

fn is_slot_marked(slots: &[bool; REGION_SLOT_COUNT], x: u8, z: u8) -> bool {
    let idx = usize::from(slot_index(x, z));
    slots[idx]
}

/// Cumulative player time in ticks.
/// Older chunks store this under `Level`, 1.18+ stores it on the chunk root.
/// Always check `Level` first: worlds upgraded past 1.18 can still contain
/// unmigrated region chunks whose `InhabitedTime` only exists under `Level`.
fn read_inhabited_time_ticks(chunk_nbt: &[u8]) -> u64 {
    let doc = match na_nbt::read_borrowed::<na_nbt::BE>(chunk_nbt) {
        Ok(doc) => doc,
        Err(_) => return 0,
    };
    let root = doc.root();

    if let Some(level) = root.get_::<na_nbt::tag::Compound>("Level") {
        if let Some(inhabited_time) = level.get_::<na_nbt::tag::Long>(INHABITED_TIME_TAG_NAME) {
            return inhabited_time.max(0) as u64;
        }
    }

    if let Some(inhabited_time) = root.get_::<na_nbt::tag::Long>(INHABITED_TIME_TAG_NAME) {
        return inhabited_time.max(0) as u64;
    }

    0
}

fn get_data_folders(
    world_folder: &Path,
    data_version: u32,
) -> Result<Vec<DataFolder>, std::io::Error> {
    if !world_folder.is_dir() {
        return Ok(Vec::new());
    }

    let mut region_directories: Vec<PathBuf> = Vec::new();

    if data_version >= DATA_VERSION_WORLD_LAYOUT_26_1_SNAPSHOT_6 {
        let dimensions = world_folder.join(DIMENSIONS_FOLDER_NAME);
        if dimensions.is_dir() {
            collect_region_directories_under(&dimensions, &mut region_directories)?;
        }
    } else {
        let root_region = world_folder.join(REGION_FOLDER_NAME);
        if root_region.is_dir() {
            region_directories.push(root_region);
        }

        for child in world_folder.read_dir()? {
            let child = match child {
                Ok(entry) => entry.path(),
                Err(err) => {
                    eprintln!("Warning: failed to read top-level world folder entry: {err}");
                    continue;
                }
            };
            if !child.is_dir() {
                continue;
            }
            let region_dir = child.join(REGION_FOLDER_NAME);
            if region_dir.is_dir() {
                region_directories.push(region_dir);
            }
        }

        let dimensions = world_folder.join(DIMENSIONS_FOLDER_NAME);
        if dimensions.is_dir() {
            collect_region_directories_under(&dimensions, &mut region_directories)?;
        }
    }

    region_directories.sort_unstable();
    region_directories.dedup();

    let mut data_folders = Vec::with_capacity(region_directories.len());
    for region_dir in region_directories {
        let Some(dimension_root) = region_dir.parent().map(Path::to_path_buf) else {
            continue;
        };
        data_folders.push(DataFolder {
            region_directory: region_dir,
            entities_directory: dimension_root.join(ENTITIES_FOLDER_NAME),
            poi_directory: dimension_root.join(POI_FOLDER_NAME),
        });
    }
    Ok(data_folders)
}

fn collect_region_directories_under(
    root: &Path,
    sink: &mut Vec<PathBuf>,
) -> Result<(), std::io::Error> {
    if !root.is_dir() {
        return Ok(());
    }

    for entry in root.read_dir()? {
        let entry = match entry {
            Ok(e) => e,
            Err(err) => {
                eprintln!(
                    "Warning: failed to read directory while collecting region dirs in {}: {}",
                    root.display(),
                    err
                );
                continue;
            }
        };

        let path = entry.path();
        if !path.is_dir() {
            continue;
        }

        if path.file_name().and_then(|n| n.to_str()) == Some(REGION_FOLDER_NAME) {
            sink.push(path);
            continue;
        }

        collect_region_directories_under(&path, sink)?;
    }

    Ok(())
}

fn parse_region_coords(path: &Path) -> Result<(i32, i32), std::io::Error> {
    let name = path
        .file_name()
        .and_then(|n| n.to_str())
        .ok_or_else(|| Error::new(ErrorKind::InvalidData, "invalid region file name"))?;
    if !name.starts_with("r.") || !name.ends_with(".mca") {
        return Err(Error::new(
            ErrorKind::InvalidData,
            "invalid region file name format",
        ));
    }
    let core = &name[2..name.len() - 4];
    let mut parts = core.split('.');
    let rx = parts
        .next()
        .ok_or_else(|| Error::new(ErrorKind::InvalidData, "missing region x"))?
        .parse::<i32>()
        .map_err(|_| Error::new(ErrorKind::InvalidData, "invalid region x"))?;
    let rz = parts
        .next()
        .ok_or_else(|| Error::new(ErrorKind::InvalidData, "missing region z"))?
        .parse::<i32>()
        .map_err(|_| Error::new(ErrorKind::InvalidData, "invalid region z"))?;
    if parts.next().is_some() {
        return Err(Error::new(
            ErrorKind::InvalidData,
            "too many region coord parts",
        ));
    }
    Ok((rx, rz))
}
