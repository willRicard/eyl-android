package io.polyhacks.eyl;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import io.polyhacks.eyl.databinding.ActivityMainBinding;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

  private final String TAG = "eyl";
  private final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";
  private final int REQUEST_CODE_PERMISSIONS = 10;

  private ActivityMainBinding viewBinding;

  private ImageCapture imageCapture;

  private ExecutorService cameraExecutor;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    viewBinding = ActivityMainBinding.inflate(getLayoutInflater());
    setContentView(viewBinding.getRoot());

    if (allPermissionsGranted()) {
      startCamera();
    } else {
      ArrayList<String> permissions = new ArrayList<>();
      permissions.add(Manifest.permission.CAMERA);
      permissions.add(Manifest.permission.RECORD_AUDIO);
      if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
      }
      ActivityCompat.requestPermissions(
          this, permissions.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
    }

    viewBinding.imageCaptureButton.setOnClickListener(
        (View view) -> {
          takePhoto();
        });

    cameraExecutor = Executors.newSingleThreadExecutor();
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == REQUEST_CODE_PERMISSIONS) {
      if (allPermissionsGranted()) {
        startCamera();
      } else {
        Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
        finish();
      }
    }
  }

  private void takePhoto() {
    if (imageCapture == null) {
      return;
    }

    String name =
        new SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis());
    ContentValues contentValues = new ContentValues();
    contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
    contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
      contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EYL");
    }

    ImageCapture.OutputFileOptions outputOptions =
        new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build();

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(this),
        new ImageCapture.OnImageSavedCallback() {
          @Override
          public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
            String msg = String.format("Photo capture succeeded: %s", output.getSavedUri());
            Toast.makeText(getBaseContext(), msg, Toast.LENGTH_SHORT).show();
            Log.d(TAG, msg);
          }

          @Override
          public void onError(@NonNull ImageCaptureException exception) {
            Log.e(TAG, String.format("Photo capture failed: %s", exception.getMessage()));
          }
        });
  }

  private void startCamera() {
    ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
        ProcessCameraProvider.getInstance(this);
    cameraProviderFuture.addListener(
        () -> {
          ProcessCameraProvider cameraProvider;
          try {
            cameraProvider = cameraProviderFuture.get();
          } catch (Exception e) {
            return;
          }

          Preview preview = new Preview.Builder().build();
          preview.setSurfaceProvider(viewBinding.viewFinder.getSurfaceProvider());

          imageCapture = new ImageCapture.Builder().build();

          ImageAnalysis imageAnalyzer = new ImageAnalysis.Builder().build();
          imageAnalyzer.setAnalyzer(
              cameraExecutor,
              new ImageAnalysis.Analyzer() {
                @Override
                public void analyze(@NonNull ImageProxy image) {
                  ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                  buffer.rewind();
                  byte[] data = new byte[buffer.remaining()];
                  buffer.get(data);

                  int luma = (int) data[0];
                  Log.d(TAG, String.format("First pixel: %d", luma));

                  image.close();
                }
              });

          try {
            cameraProvider.unbindAll();

            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, imageAnalyzer);
          } catch (Exception exception) {
            Log.e(TAG, "Use case binding failed", exception);
          }
        },
        ContextCompat.getMainExecutor(this));
  }

  public boolean allPermissionsGranted() {
    boolean result =
        ContextCompat.checkSelfPermission(getBaseContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
      result &=
          ContextCompat.checkSelfPermission(
                  getBaseContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
              == PackageManager.PERMISSION_GRANTED;
    }
    return result;
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    cameraExecutor.shutdown();
  }
}
