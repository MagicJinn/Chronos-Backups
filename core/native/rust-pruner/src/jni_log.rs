use jni::objects::{JClass, JValue};
use jni::JNIEnv;

pub fn log_info(env: &mut JNIEnv, class: &JClass, message: &str) {
    forward(env, class, "logNativeInfo", message);
}

pub fn log_warn(env: &mut JNIEnv, class: &JClass, message: &str) {
    forward(env, class, "logNativeWarn", message);
}

pub fn log_error(env: &mut JNIEnv, class: &JClass, message: &str) {
    forward(env, class, "logNativeError", message);
}

fn forward(env: &mut JNIEnv, class: &JClass, method: &str, message: &str) {
    let Ok(jmsg) = env.new_string(message) else {
        eprintln!("{message}");
        return;
    };
    if let Err(err) = env.call_static_method(
        class,
        method,
        "(Ljava/lang/String;)V",
        &[JValue::Object(&jmsg)],
    ) {
        eprintln!("Warning: failed to forward native log line to Java: {err}");
        eprintln!("{message}");
    }
}
