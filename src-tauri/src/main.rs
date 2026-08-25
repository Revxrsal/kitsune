use jni::JavaVM;
use kitsune_app_lib::{commands, events, jvm};
use std::time::Duration;

fn main() -> anyhow::Result<()> {
    let dist = jvm::JavaDist::locate()?;
    jvm::run(&dist)?;

    // Once, up front: load `Bridge`, cache its class + method IDs and register
    // `rustComplete`. Every later call reuses the cache.
    let vm = JavaVM::singleton()?;
    vm.attach_current_thread(|env| -> Result<(), jni::errors::Error> {
        commands::bridge::init(env)?;
        events::bridge::init(env)?;
        Ok(())
    })?;

    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .max_blocking_threads(8)
        .thread_keep_alive(Duration::from_secs(3600)) // stop blocking-pool churn
        .on_thread_start(|| {
            // permanent attachment; lasts for the life of the worker
            let vm = JavaVM::singleton().unwrap();
            vm.attach_current_thread(|env| -> Result<(), jni::errors::Error> {
                commands::bridge::init(env)?;
                events::bridge::init(env)?;
                Ok(())
            })
            .unwrap();
        })
        .enable_all()
        .build()?;
    let handle = rt.handle().clone();
    tauri::async_runtime::set(handle);
    Ok(rt.block_on(async move {
        kitsune_app_lib::run().await;
    }))
}
