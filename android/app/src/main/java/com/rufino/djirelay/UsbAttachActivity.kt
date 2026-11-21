package com.rufino.djirelay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.rufino.djirelay.ui.MainActivity

/**
 * Started by Android when the RC-N1 (USB accessory) is plugged in. As in SampleCode-V5,
 * it only brings MainActivity to the front and finishes. MainActivity is singleTask, so
 * an existing instance is reused instead of being recreated.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
