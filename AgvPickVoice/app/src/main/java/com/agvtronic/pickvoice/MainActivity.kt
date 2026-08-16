package com.agvtronic.pickvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        Surface {
          // TODO(#mirror-screen): replace with real navigation (pairing -> orderlist ->
          // operation -> divergence), doc §12.
          Text("AGV Pick Voice")
        }
      }
    }
  }
}
