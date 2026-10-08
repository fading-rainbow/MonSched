package cn.qingke.schedule.importer

import java.net.URI

object SchoolConnection {
    const val QIT_URL = "http://jw.qit.edu.cn/jwglxt/xtgl/login_slogin.html"
    val systems = linkedMapOf("zhengfang" to "正方教务（新版／经典）", "qiangzhi" to "强智教务", "eams" to "树维 / URP 等教务", "generic" to "通用课表页面")
    fun label(system: String): String = systems[system] ?: systems.getValue("generic")
    fun valid(url: String, configuredUrl: String = url, allowHttp: Boolean = false): Boolean = runCatching {
        val uri = URI(url)
        if (uri.userInfo != null || uri.host.isNullOrBlank()) return@runCatching false
        uri.scheme.equals("https", true) || uri.scheme.equals("http", true) &&
            (uri.host.equals("jw.qit.edu.cn", true) || allowHttp && uri.host.equals(URI(configuredUrl).host, true) && uri.port == URI(configuredUrl).port)
    }.getOrDefault(false)
    fun zhengfangEntry(url: String): String? = runCatching {
        val uri = URI(url)
        val marker = listOf("/xtgl/", "/kbcx/").firstOrNull { uri.path.orEmpty().contains(it) } ?: return@runCatching null
        URI(uri.scheme, uri.authority, uri.path.substringBefore(marker) + "/kbcx/xskbcx_cxXskbcxIndex.html", "gnmkdm=N2151", null).toString()
    }.getOrNull()
}
