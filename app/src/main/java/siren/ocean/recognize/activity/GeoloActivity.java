package siren.ocean.recognize.activity;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import siren.ocean.recognize.AppContext;
import siren.ocean.recognize.R;

public class GeoloActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_geolo);
        findViewById(R.id.button).setOnClickListener(v -> {
            if (AppContext.isRunning){
                Toast.makeText(v.getContext(), "底层运行中，请骚等", Toast.LENGTH_LONG).show();
            }else{
                Intent intent = new Intent(this, MainActivity.class);
                this.startActivity(intent);
            }
        });
    }
}
