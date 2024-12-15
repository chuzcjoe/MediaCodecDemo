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
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String SAMPLE_VIDEO_PATH = Environment.getExternalStorageDirectory() + "/Download/sample.mp4";
    private MediaCodec decoder;
    private MediaExtractor extractor;
    private boolean isPlaying = false;
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final int PICK_VIDEO_REQUEST = 1;
    private String selectedVideoPath = null;
    private int videoWidth = 0;
    private int videoHeight = 0;
    private boolean isPaused = false;
    private final Object pauseLock = new Object();
    private SurfaceView mSurfaceView;
    private ConstraintLayout mParent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_VIDEO},
                        PERMISSION_REQUEST_CODE);
                return;
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE);
                return;
            }
        }

        findViewById(R.id.selectVideoButton).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            startActivityForResult(intent, PICK_VIDEO_REQUEST);
        });

        SurfaceView surfaceView = findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(this);
        setupTapListener();

        mSurfaceView = findViewById(R.id.surfaceView);
        mParent = findViewById(R.id.main);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO_REQUEST && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri videoUri = data.getData();
                try {
                    // Take persistent permissions with correct flags
                    final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(videoUri, takeFlags);
                    selectedVideoPath = videoUri.toString();
                    
                    // Restart video playback with new file
                    releaseResources();
                    SurfaceView surfaceView = findViewById(R.id.surfaceView);
                    if (surfaceView.getHolder().getSurface().isValid()) {
                        initializeDecoder(surfaceView.getHolder().getSurface());
                    }
                } catch (SecurityException e) {
                    e.printStackTrace();
                    Toast.makeText(this, "Error accessing video: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
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
            
            boolean videoTrackFound = false;
            // Find the first video track
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    extractor.selectTrack(i);
                    
                    // Get video dimensions
                    if (format.containsKey(MediaFormat.KEY_WIDTH) && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                        videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                        videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                        adjustAspectRatio();
                    }
                    
                    decoder = MediaCodec.createDecoderByType(mime);
                    decoder.configure(format, surface, null, 0);
                    videoTrackFound = true;
                    break;
                }
            }

            if (!videoTrackFound) {
                Toast.makeText(this, "No video track found in the file", Toast.LENGTH_SHORT).show();
                return;
            }

            if (decoder == null) {
                Toast.makeText(this, "Failed to create decoder", Toast.LENGTH_SHORT).show();
                return;
            }

            decoder.start();
            startDecoding();

        } catch (IOException e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, 
                "Error initializing decoder: " + e.getMessage(), Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, 
                "Unexpected error: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void startDecoding() {
        isPlaying = true;
        Thread decodingThread = new Thread(() -> {
            try {
                if (decoder == null || extractor == null) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, 
                        "Decoder or extractor is null", Toast.LENGTH_SHORT).show());
                    return;
                }

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean isEOS = false;
                long startMs = System.currentTimeMillis();

                while (!isEOS && isPlaying) {
                    // Handle pause state
                    synchronized (pauseLock) {
                        while (isPaused && isPlaying) {
                            try {
                                pauseLock.wait();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    if (!isEOS) {
                        int inIndex = decoder.dequeueInputBuffer(10000);
                        if (inIndex >= 0) {
                            ByteBuffer buffer = decoder.getInputBuffer(inIndex);
                            buffer.clear();
                            int sampleSize = extractor.readSampleData(buffer, 0);
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                isEOS = true;
                            } else {
                                long presentationTimeUs = extractor.getSampleTime();
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0);
                                extractor.advance();
                            }
                        }
                    }

                    int outIndex = decoder.dequeueOutputBuffer(info, 10000);
                    switch (outIndex) {
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            break;
                        default:
                            if (outIndex >= 0) {
                                // Adjust presentation time when paused
                                if (!isPaused) {
                                    long sleepTime = info.presentationTimeUs / 1000 - (System.currentTimeMillis() - startMs);
                                    if (sleepTime > 0) {
                                        try {
                                            Thread.sleep(sleepTime);
                                        } catch (InterruptedException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                }
                                decoder.releaseOutputBuffer(outIndex, !isPaused);
                            }
                    }

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, 
                    "Decoding error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                releaseResources();
            }
        }, "DecodingThread");
        
        decodingThread.start();
    }

    private void releaseResources() {
        isPlaying = false;
        synchronized (pauseLock) {
            isPaused = false;
            pauseLock.notify();
        }
        // Hide pause icon when releasing resources
        runOnUiThread(() -> {
            ImageView pauseIcon = findViewById(R.id.pauseIcon);
            pauseIcon.setVisibility(View.GONE);
        });
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                recreate();
            } else {
                Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void adjustAspectRatio() {
        if (videoWidth == 0 || videoHeight == 0) {
            return;
        }
        
        mSurfaceView.post(() -> {
            int parentWidth = mParent.getWidth();
            int parentHeight = mParent.getHeight();
            
            float videoAspectRatio = (float) videoWidth / videoHeight;
            float screenAspectRatio = (float) parentWidth / parentHeight;
            
            int newWidth, newHeight;
            
            if (videoAspectRatio > screenAspectRatio) {
                // Video is wider than screen
                newWidth = parentWidth;
                newHeight = (int) (parentWidth / videoAspectRatio);
            } else {
                // Video is taller than screen
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

    private void setupTapListener() {
        SurfaceView surfaceView = findViewById(R.id.surfaceView);
        ImageView pauseIcon = findViewById(R.id.pauseIcon);
        
        surfaceView.setOnClickListener(v -> {
            synchronized (pauseLock) {
                isPaused = !isPaused;
                if (!isPaused) {
                    pauseLock.notify();
                }
                // Update pause icon visibility
                pauseIcon.setVisibility(isPaused ? View.VISIBLE : View.GONE);
            }
        });
    }
}