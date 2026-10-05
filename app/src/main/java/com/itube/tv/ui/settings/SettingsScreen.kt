package com.itube.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.itube.tv.BuildConfig
import com.itube.tv.data.AppSettings
import com.itube.tv.data.SPEEDS
import com.itube.tv.data.YouTube
import com.itube.tv.data.cycle
import com.itube.tv.player.Codecs
import com.itube.tv.ui.LocalContainer
import com.itube.tv.ui.LocalShell
import com.itube.tv.ui.components.FocusSurface
import com.itube.tv.ui.components.SideListItem
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T
import com.itube.tv.update.UpdateState

private enum class Section(val label: String, val icon: ImageVector) {
    PLAYBACK("Lecture", Icons.Rounded.PlayCircle),
    CONTENT("Contenu", Icons.Rounded.Tune),
    ABOUT("À propos", Icons.Rounded.Info),
}

private val SUBTITLE_LANGS = listOf("" to "Désactivés", "fr" to "Français", "en" to "Anglais", "es" to "Espagnol", "de" to "Allemand", "it" to "Italien")

private fun <T> List<T>.after(current: T): T = this[(indexOf(current) + 1).mod(size)]

fun speedLabel(speed: Float): String = if (speed == 1f) "Normale" else (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().replace('.', ',')) + "×"

@Composable
fun SettingsScreen() {
    var section by rememberSaveable { mutableStateOf(Section.PLAYBACK) }
    Row(Modifier.fillMaxSize().padding(start = 36.dp, end = 48.dp, top = 6.dp)) {
        Column(Modifier.width(250.dp).fillMaxHeight().focusRestorer(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Section.entries.forEach { s ->
                SideListItem(s.label, selected = s == section, icon = s.icon, onClick = { section = s }, onFocused = { section = s })
            }
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            when (section) {
                Section.PLAYBACK -> PlaybackSection()
                Section.CONTENT -> ContentSection()
                Section.ABOUT -> AboutSection()
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    value: String? = null,
    subtitle: String? = null,
    chevron: Boolean = false,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(if (subtitle != null) 64.dp else 52.dp),
        shape = RoundedCornerShape(12.dp),
        color = C.Surface,
        focusedScale = 1.02f,
        elevation = 10.dp,
    ) {
        val content = LocalContentColor.current
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = T.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = T.Footnote, color = content.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (value != null) Text(value, style = T.Callout, color = content.copy(alpha = 0.6f), maxLines = 1)
            if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(22.dp), tint = content.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun SettingsList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp, start = 6.dp, end = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, style = T.Footnote, color = C.Text3, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 2.dp))
}

@Composable
private fun rememberSettings(): Pair<AppSettings, ((AppSettings) -> AppSettings) -> Unit> {
    val container = LocalContainer.current
    val s by container.settings.flow.collectAsState()
    val update: ((AppSettings) -> AppSettings) -> Unit = { container.settings.update(it) }
    return s to update
}

private fun onOff(b: Boolean) = if (b) "Activé" else "Désactivé"

@Composable
private fun PlaybackSection() {
    val (s, update) = rememberSettings()
    val codecs = remember { Codecs.summary() }
    SettingsList {
        item { GroupTitle("Image") }
        item {
            SettingRow("Qualité maximale", s.maxQuality.label, "Limitée à ce que votre téléviseur sait décoder") {
                update { it.copy(maxQuality = it.maxQuality.cycle(-1)) }
            }
        }
        item {
            SettingRow("Codec vidéo", s.codec.label, "Décodage matériel : $codecs") {
                update { it.copy(codec = it.codec.cycle()) }
            }
        }
        item {
            SettingRow("Mémoire tampon", s.bufferMode.label, "« Stable » pour une connexion irrégulière") {
                update { it.copy(bufferMode = it.bufferMode.cycle()) }
            }
        }
        item { GroupTitle("Lecture") }
        item {
            SettingRow("Lecture automatique", onOff(s.autoplayNext), "Enchaîne sur la vidéo suivante à la fin") {
                update { it.copy(autoplayNext = !it.autoplayNext) }
            }
        }
        item { SettingRow("Vitesse par défaut", speedLabel(s.speed)) { update { it.copy(speed = SPEEDS.after(it.speed)) } } }
        item {
            SettingRow("Sous-titres", SUBTITLE_LANGS.firstOrNull { it.first == s.subtitleLang }?.second ?: s.subtitleLang, "Affichés automatiquement quand ils existent") {
                update { it.copy(subtitleLang = SUBTITLE_LANGS.map { l -> l.first }.after(it.subtitleLang)) }
            }
        }
        item { GroupTitle("SponsorBlock") }
        item {
            SettingRow("Passer les sponsors", onOff(s.sponsorBlock), "Saute les placements de produits signalés par la communauté") {
                update { it.copy(sponsorBlock = !it.sponsorBlock) }
            }
        }
        if (s.sponsorBlock) item {
            SettingRow("Passer aussi autopromo, intros et rappels", onOff(s.sponsorBlockExtended), "« Abonnez-vous », génériques, promotions de la chaîne") {
                update { it.copy(sponsorBlockExtended = !it.sponsorBlockExtended) }
            }
        }
    }
}

@Composable
private fun ContentSection() {
    val (s, update) = rememberSettings()
    val shell = LocalShell.current
    SettingsList {
        item {
            SettingRow("Masquer les Shorts", onOff(s.hideShorts), "Dans les abonnements, la recherche et les chaînes") {
                update { it.copy(hideShorts = !it.hideShorts) }
            }
        }
        item {
            SettingRow("Région", s.region.label, "Langue et pays des tendances et de la recherche") {
                val next = s.region.cycle()
                update { it.copy(region = next) }
                YouTube.init(next.language, next.country)
                shell.toast("Région : ${next.label}")
            }
        }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val shell = LocalShell.current
    val (s, update) = rememberSettings()
    val updater = LocalContainer.current.updater
    val updateState by updater.flow.collectAsState()
    SettingsList {
        item { SettingRow("Version", BuildConfig.VERSION_NAME) {} }
        if (updater.supported) {
            item {
                val st = updateState
                val (title, subtitle) = when (st) {
                    is UpdateState.Available -> "Installer la version ${st.release.versionName}" to "Une nouvelle version est disponible"
                    is UpdateState.Checking -> "Rechercher une mise à jour" to "Vérification…"
                    is UpdateState.UpToDate -> "Rechercher une mise à jour" to "iTube est à jour"
                    is UpdateState.Downloading -> "Mise à jour en cours" to "Téléchargement… ${(st.progress * 100).toInt()} %"
                    is UpdateState.Installing -> "Mise à jour en cours" to "Installation…"
                    is UpdateState.Failed -> (st.release?.let { "Réessayer la mise à jour" } ?: "Rechercher une mise à jour") to st.message
                    UpdateState.Idle -> "Rechercher une mise à jour" to "Télécharge et installe la dernière version"
                }
                SettingRow(title, subtitle = subtitle) {
                    when (st) {
                        is UpdateState.Available -> updater.install(st.release)
                        is UpdateState.Failed -> st.release?.let { updater.install(it) } ?: updater.check()
                        is UpdateState.Downloading, is UpdateState.Checking -> Unit
                        else -> updater.check()
                    }
                }
            }
            item {
                SettingRow("Vérifier automatiquement les mises à jour", onOff(s.autoUpdateCheck), "Important : YouTube change souvent, les mises à jour corrigent la lecture") {
                    update { it.copy(autoUpdateCheck = !it.autoUpdateCheck) }
                }
            }
        }
        item {
            SettingRow("Vider le cache des images", subtitle = "Libère de l'espace de stockage") {
                val loader = SingletonImageLoader.get(context)
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
                shell.toast("Cache vidé")
            }
        }
        item {
            Text(
                "Télécommande — OK : lecture / pause · ◀ ▶ : reculer / avancer de 10 s (maintenir pour accélérer) · " +
                    "▼ : infos, qualité, sous-titres, vitesse et vidéos suivantes · Maintenir OK sur une vidéo : plus d'options.\n\n" +
                    "iTube lit YouTube sans compte et sans publicité grâce à NewPipeExtractor ; les sponsors intégrés aux vidéos " +
                    "sont sautés grâce à SponsorBlock. Vos abonnements et votre historique restent sur ce téléviseur.",
                style = T.Subhead,
                color = C.Text3,
                modifier = Modifier.padding(start = 6.dp, top = 12.dp, end = 40.dp),
            )
        }
    }
}

