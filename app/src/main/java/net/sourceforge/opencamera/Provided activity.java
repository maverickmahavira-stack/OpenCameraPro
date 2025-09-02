package net.sourceforge.opencamera;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class ProVideoActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pro_video);

        Button recordButton = findViewById(R.id.record_button);
        recordButton.setOnClickListener(v ->
            Toast.makeText(this, "Pro Video recording (placeholder)", Toast.LENGTH_SHORT).show()
        );
    }
}
