use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::sync::Mutex;
use std::sync::OnceLock;

use jni::JNIEnv;
use jni::EnvUnowned;
use jni::objects::JClass;
use jni::objects::JString;

use veilid_core::{VeilidAPI, api_startup, VeilidConfig};

static VEILID_API: OnceLock<Mutex<Option<VeilidAPI>>> = OnceLock::new();
static TOKIO_RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();

fn get_api() -> Option<VeilidAPI> {
    VEILID_API.get()?.lock().ok()?.as_ref().cloned()
}

fn set_api(api: Option<VeilidAPI>) {
    if let Some(mutex) = VEILID_API.get() {
        if let Ok(mut guard) = mutex.lock() {
            *guard = api;
        }
    }
}

// ==================== C-ABI functions ====================

#[no_mangle]
pub extern "C" fn veilid_init(program_name: *const c_char, namespace: *const c_char) -> c_int {
    let program_name = unsafe {
        if program_name.is_null() { return -1; }
        match CStr::from_ptr(program_name).to_str() {
            Ok(s) => s.to_string(),
            Err(_) => return -1,
        }
    };
    let _namespace = unsafe {
        if namespace.is_null() { return -1; }
        match CStr::from_ptr(namespace).to_str() {
            Ok(s) => s.to_string(),
            Err(_) => return -1,
        }
    };

    let runtime = match tokio::runtime::Runtime::new() {
        Ok(r) => r,
        Err(_) => return -2,
    };

    let config = VeilidConfig::new(&program_name, "canion", "rebirth", None, None);

    let update_callback: veilid_core::UpdateCallback = std::sync::Arc::new(|_| {});

    let result = runtime.block_on(async {
        veilid_core::api_startup(update_callback, config).await
    });

    match result {
        Ok(api) => {
            VEILID_API.get_or_init(|| Mutex::new(None));
            set_api(Some(api));
            TOKIO_RUNTIME.get_or_init(|| runtime);
            0
        }
        Err(_) => -3,
    }
}

#[no_mangle]
pub extern "C" fn veilid_get_state() -> c_int {
    match get_api() {
        Some(_) => 2,
        None => -1,
    }
}

#[no_mangle]
pub extern "C" fn veilid_shutdown() {
    set_api(None);
}

#[no_mangle]
pub extern "C" fn veilid_free_string(s: *mut c_char) {
    if !s.is_null() {
        unsafe { let _ = CString::from_raw(s); }
    }
}

#[no_mangle]
pub extern "C" fn veilid_version() -> *mut c_char {
    CString::new("0.5.7").unwrap().into_raw()
}

// ==================== JNI functions ====================

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_org_caniorebirth_android_VeilidBridge_veilidInit(
    mut env: EnvUnowned,
    _class: JClass,
    program_name: JString,
    namespace: JString,
) -> c_int {
    env.with_env(|env| -> jni::errors::Result<c_int> {
        let pn: String = env.get_string(&program_name)?.into();
        let ns: String = env.get_string(&namespace)?.into();
        let pn_c = CString::new(pn).unwrap_or_default();
        let ns_c = CString::new(ns).unwrap_or_default();
        Ok(veilid_init(pn_c.as_ptr(), ns_c.as_ptr()))
    }).resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_org_caniorebirth_android_VeilidBridge_veilidGetState(
    _env: EnvUnowned,
    _class: JClass,
) -> c_int {
    veilid_get_state()
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_org_caniorebirth_android_VeilidBridge_veilidShutdown(
    _env: EnvUnowned,
    _class: JClass,
) {
    veilid_shutdown()
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_org_caniorebirth_android_VeilidBridge_veilidVersion(
    mut env: EnvUnowned,
    _class: JClass,
) -> jni::sys::jstring {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
        let s = env.new_string("0.5.7")?;
        Ok(s.into_raw())
    }).resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
