package com.panomc.plugins.sociallogin.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

class UnlinkedSocialAccountLog(
    userId: Long,
    username: String,
    targetUsername: String,
    provider: String,
    pluginId: String
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject()
        .put("username", username)
        .put("targetUsername", targetUsername)
        .put("provider", provider)
)
