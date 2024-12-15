package com.example.mediacodecdemo;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String SAMPLE_VIDEO_PATH = Environment.getExternalStorageDirectory() + "/Download/sample.mp4";
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final int PICK_VIDEO_REQUEST = 1;

    private MediaCodec decoder;
    private MediaExtractor extractor;
    private String selectedVideoPath = null;
    private int videoWidth = 0;
    private int videoHeight = 0;
    private SurfaceView mSurfaceView;
    private ConstraintLayout mParent;
    private long startMs = 0;

    private final MediaCodec.Callback callback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {
            ByteBuffer inputBuffer = codec.getInputBuffer(index);
            if (inputBuffer == null) return;

            int sampleSize = extractor.readSampleData(inputBuffer, 0);
            long presentationTimeUs = 0;

            if (sampleSize < 0) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            } else {
                presentationTimeUs = extractor.getSampleTime();
                codec.queueInputBuffer(index, 0, sampleSize, presentationTimeUs, 0);
                extractor.advance();
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            if (startMs == 0) {
                startMs = System.currentTimeMillis();
            }

            long presentationTimeMs = info.presentationTimeUs / 1000;
            long elapsedTimeMs = System.currentTimeMillis() - startMs;
            long sleepTimeMs = presentationTimeMs - elapsedTimeMs;

            if (sleepTimeMs > 0) {
                try {
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            codec.releaseOutputBuffer(index, true);
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            e.printStackTrace();
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            // Handle format changes if needed
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        if (!checkAndRequestPermissions()) {
            return;
        }

        findViewById(R.id.selectVideoButton).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            startActivityForResult(intent, PICK_VIDEO_REQUEST);
        });

        mSurfaceView = findViewById(R.id.surfaceView);
        mSurfaceView.getHolder().addCallback(this);
        mParent = findViewById(R.id.main);
    }

    private boolean checkAndRequestPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_VIDEO},
                        PERMISSION_REQUEST_CODE);
                return false;
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE);
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri videoUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(
                    videoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                selectedVideoPath = videoUri.toString();
                releaseResources();
                if (mSurfaceView.getHolder().getSurface().isValid()) {
                    initializeDecoder(mSurfaceView.getHolder().getSurface());
                }
            } catch (SecurityException e) {
                e.printStackTrace();
                Toast.makeText(this, "Error accessing video: " + e.getMessage(), 
                    Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void initializeDecoder(Surface surface) {
        try {
            extractor = new MediaExtractor();
            if (selectedVideoPath != null) {
                extractor.setDataSource(this, Uri.parse(selectedVideoPath), null);
            } else {
                File videoFile = new File(SAMPLE_VIDEO_PATH);
                if (!videoFile.exists()) {
                    Toast.makeText(this, "Please select a video file", Toast.LENGTH_LONG).show();
                    return;
                }
                extractor.setDataSource(SAMPLE_VIDEO_PATH);
            }
            
            configureCodec(surface);
        } catch (IOException e) {
            handleError("Error initializing decoder", e);
        } catch (Exception e) {
            handleError("Unexpected error", e);
        }
    }

    private void configureCodec(Surface surface) throws IOException {
        startMs = 0;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                extractor.selectTrack(i);
                if (format.containsKey(MediaFormat.KEY_WIDTH) 
                        && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                    videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                    videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                    adjustAspectRatio();
                }
                
                decoder = MediaCodec.createDecoderByType(mime);
                decoder.setCallback(callback);
                // decoder mode
                decoder.configure(format, surface, null, 0);
                decoder.start();
                return;
            }
        }
        Toast.makeText(this, "No video track found", Toast.LENGTH_SHORT).show();
    }

    private void handleError(String message, Exception e) {
        e.printStackTrace();
        runOnUiThread(() -> Toast.makeText(this, message + ": " + e.getMessage(), 
            Toast.LENGTH_LONG).show());
    }

    private void releaseResources() {
        if (decoder != null) {
            decoder.stop();
            decoder.release();
            decoder = null;
        }
        if (extractor != null) {
            extractor.release();
            extractor = null;
        }
    }

    private void adjustAspectRatio() {
        if (videoWidth == 0 || videoHeight == 0) return;
        
        mSurfaceView.post(() -> {
            int parentWidth = mParent.getWidth();
            int parentHeight = mParent.getHeight();
            
            float videoAspectRatio = (float) videoWidth / videoHeight;
            float screenAspectRatio = (float) parentWidth / parentHeight;
            
            int newWidth, newHeight;
            if (videoAspectRatio > screenAspectRatio) {
                newWidth = parentWidth;
                newHeight = (int) (parentWidth / videoAspectRatio);
            } else {
                newHeight = parentHeight;
                newWidth = (int) (parentHeight * videoAspectRatio);
            }
            
            ConstraintLayout.LayoutParams params = 
                (ConstraintLayout.LayoutParams) mSurfaceView.getLayoutParams();
            params.width = newWidth;
            params.height = newHeight;
            params.horizontalBias = 0.5f;
            params.verticalBias = 0.5f;
            mSurfaceView.setLayoutParams(params);
        });
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        initializeDecoder(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (videoWidth > 0 && videoHeight > 0) {
            adjustAspectRatio();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseResources();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseResources();
    }
}