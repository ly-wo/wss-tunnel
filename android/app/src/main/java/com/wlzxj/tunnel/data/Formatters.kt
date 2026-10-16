package com.wlzxj.tunnel.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatters {
    private val zone = ZoneId.systemDefault()
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun epochSeconds(seconds: Long): String =
        dateTime.format(Instant.ofEpochSecond(seconds).atZone(zone))

    fun auditActionLabel(action: String): String = when (action) {
        "register" -> "注册"
        "login" -> "登录"
        "logout" -> "退出"
        "token_rotate" -> "重置 Token"
        "tunnel_connect" -> "隧道连接"
        "tunnel_disconnect" -> "隧道断开"
        "domain_delete" -> "删除域名"
        else -> action
    }

    fun auditOutcomeLabel(outcome: String): String = when (outcome) {
        "success" -> "成功"
        "failure" -> "失败"
        else -> outcome
    }
}
