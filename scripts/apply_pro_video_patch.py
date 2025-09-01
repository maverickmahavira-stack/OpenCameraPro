#!/usr/bin/env python3
# Phase 0: Pro Video scaffold for Open Camera (modern repo)

import os, re

APP_DIR = os.path.join("app", "src", "main")
JAVA_DIR = os.path.join(APP_DIR, "java", "net", "sourceforge", "opencamera", "provideo")
RES_LAYOUT_DIR = os.path.join(APP_DIR, "res", "layout")
RES_VALUES_DIR = os.path.join(APP_DIR, "res", "values")
MANIFEST = os.path.join(APP_DIR, "AndroidManifest.xml")

def ensure_dirs():
    os.makedirs(JAVA_DIR, exist_ok=True)
    os.makedirs(RES_LAYOUT_DIR, exist_ok=True)
    os.makedirs(RES_VALUES_DIR, exist_ok=True)

PRO_VIDEO_ACTIVITY = """package net.sourceforge.opencamera.provideo;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class ProVideoActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Open Camera – Pro Video (Preview)");
        setContentView(R.layout.activity_pro_video);

        TextView tv = findViewById(R.id.pro_video_status);
        if (tv != null) {
            tv.setText("Pro Video Mode installed.\\nNext step: EGL + MediaCodec pipeline.");
        }
    }
}
"""

PRO_VIDEO_LAYOUT = """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:id="@+id/pro_video_status"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="@string/pro_video_ready"
        android:textSize="18sp"
        android:padding="24dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>
</androidx.constraintlayout.widget.ConstraintLayout>
"""

PRO_VIDEO_STRINGS = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="pro_video_ready">Pro Video scaffold is installed. If you see this screen, patching worked.</string>
</resources>
"""

def write_file(path, content):
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)

def patch_manifest():
    with open(MANIFEST, "r", encoding="utf-8") as f:
        manifest = f.read()

    if "net.sourceforge.opencamera.provideo.ProVideoActivity" in manifest:
        print("Manifest already patched.")
        return

    activity_snippet = """
        <activity
            android:name="net.sourceforge.opencamera.provideo.ProVideoActivity"
            android:exported="true"
            android:label="Open Camera – Pro Video">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    """

    new_manifest = re.sub(r"</application>", activity_snippet + "\n    </application>", manifest, count=1)

    with open(MANIFEST, "w", encoding="utf-8") as f:
        f.write(new_manifest)

def main():
    ensure_dirs()
    write_file(os.path.join(JAVA_DIR, "ProVideoActivity.java"), PRO_VIDEO_ACTIVITY)
    write_file(os.path.join(RES_LAYOUT_DIR, "activity_pro_video.xml"), PRO_VIDEO_LAYOUT)
    write_file(os.path.join(RES_VALUES_DIR, "strings_pro_video.xml"), PRO_VIDEO_STRINGS)
    patch_manifest()
    print("Phase 0 patch applied successfully.")

if __name__ == "__main__":
    main()
