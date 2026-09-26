package run.moritz.quantify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val viewModel: CountViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val colors =
                if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
                else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = colors) { CountScreen(viewModel) }
        }
    }
}
