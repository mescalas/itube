package com.itube.tv.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.itube.tv.data.account.SignInState
import com.itube.tv.data.account.YouTubeAccount
import com.itube.tv.ui.components.Loading
import com.itube.tv.ui.components.PillButton
import com.itube.tv.ui.components.tryFocus
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T
import kotlinx.coroutines.delay

/** "Enter this code on your phone" screen of the Google sign-in (device flow). */
@Composable
fun SignInDialog(account: YouTubeAccount, onDone: (String?) -> Unit) {
    val state by account.state.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(state) {
        val s = state
        if (s is SignInState.SignedIn) onDone(s.account?.name)
        delay(80)
        focus.tryFocus()
    }
    Dialog(onDismissRequest = { account.cancelSignIn(); onDone(null) }) {
        Column(
            Modifier.width(720.dp).clip(RoundedCornerShape(28.dp)).background(C.Surface).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Connexion à YouTube", style = T.Title2)
            Spacer(Modifier.height(20.dp))
            when (val s = state) {
                is SignInState.WaitingForCode -> Row(verticalAlignment = Alignment.CenterVertically) {
                    val qr = remember(s.url) { qrCode(s.url) }
                    Box(Modifier.size(196.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
                        Image(qr.asImageBitmap(), null, Modifier.size(176.dp))
                    }
                    Spacer(Modifier.width(32.dp))
                    Column(Modifier.width(400.dp)) {
                        Text("1. Sur votre téléphone, scannez le code ou ouvrez", style = T.Callout, color = C.Text2)
                        Text(s.url.removePrefix("https://"), style = T.Title3)
                        Spacer(Modifier.height(14.dp))
                        Text("2. Saisissez ce code :", style = T.Callout, color = C.Text2)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            s.userCode,
                            style = T.LargeTitle.copy(fontSize = 44.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold),
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("3. Choisissez votre compte et autorisez. Cette fenêtre se fermera toute seule.", style = T.Footnote, color = C.Text3)
                    }
                }
                is SignInState.Failed -> Text(s.message, style = T.Callout, color = C.Text2, textAlign = TextAlign.Center)
                else -> Loading(Modifier.height(160.dp), "Préparation…")
            }
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state is SignInState.Failed) {
                    PillButton("Réessayer", onClick = { account.startSignIn() }, primary = true, modifier = Modifier.focusRequester(focus))
                }
                PillButton(
                    "Annuler",
                    onClick = { account.cancelSignIn(); onDone(null) },
                    modifier = if (state is SignInState.Failed) Modifier else Modifier.focusRequester(focus),
                )
            }
        }
    }
}

private fun qrCode(text: String, size: Int = 360): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
