fn main() {
    let world_name = std::env::args().nth(1);
    std::process::exit(rust_pruner::run_prune_world_test(
        world_name.as_deref(),
    ));
}
