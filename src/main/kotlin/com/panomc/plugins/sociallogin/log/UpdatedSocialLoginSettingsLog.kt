package com.panomc.plugins.sociallogin.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

class UpdatedSocialLoginSettingsLog(
    userId: Long,
    username: String,
    pluginId: String
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject()
        .put("username", username)
)
