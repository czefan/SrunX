package io.github.czefan.srunx.service

object BgIpcProtocol {
    const val KEY_SAVE_DRAFT = "save_draft"
    const val MSG_REGISTER_CLIENT = 1
    const val MSG_UNREGISTER_CLIENT = 2
    const val MSG_SAVE_CONFIG = 3
    const val MSG_LOGIN_NOW = 4
    const val MSG_LOGOUT_NOW = 5
    const val MSG_REFRESH_STATUS = 6
    const val MSG_SET_AUTO_MONITOR = 7
    const val MSG_SET_AUTO_BOOT = 8
    const val MSG_CLEAR_LOGS = 9

    const val MSG_STATE_SNAPSHOT = 101
    const val MSG_EVENT_MESSAGE = 102

    const val KEY_CONFIG_JSON = "config_json"
    const val KEY_RUNTIME_JSON = "runtime_json"
    const val KEY_LOGS_JSON = "logs_json"
    const val KEY_MESSAGE = "message"
    const val KEY_ENABLED = "enabled"
}
