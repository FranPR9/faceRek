package com.weex.franciscopaniagua.facerek;


import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;
import com.google.firebase.ml.vision.common.FirebaseVisionPoint;
import com.google.firebase.ml.vision.face.FirebaseVisionFace;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetector;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceLandmark;

import java.util.Arrays;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;

public class FaceRecognitionActivity extends AppCompatActivity {

    private static final String TAG = "FaceRecognitionActivity";
    @BindView(R.id.preview)
    TextureView preview;
    private SurfaceTexture mPreviewSurfaceTexture;
    private CameraDevice mCamera;
    private CameraCaptureSession mSession;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;
    private ImageReader jpegImageReader;
    private ImageReader rawImageReader;
    private String cameraId;
    private FirebaseVisionFaceDetectorOptions options;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_face_recognition);
        ButterKnife.bind(this);
        options =
                new FirebaseVisionFaceDetectorOptions.Builder()
                        .setModeType(FirebaseVisionFaceDetectorOptions.ACCURATE_MODE)
                        .setLandmarkType(FirebaseVisionFaceDetectorOptions.ALL_LANDMARKS)
                        .setClassificationType(FirebaseVisionFaceDetectorOptions.ALL_CLASSIFICATIONS)
                        .setMinFaceSize(0.15f)
                        .setTrackingEnabled(true)
                        .build();
        preview.setSurfaceTextureListener(surfaceTextureListener);

    }

    TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            mPreviewSurfaceTexture = surface;
            try {
                previewSurface = new Surface(mPreviewSurfaceTexture);
                setCamera();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            // to next step...

        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }

    };

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

    }

    Size[] rawSizes;
    Size[] jpegSizes;

    private void setCamera() throws CameraAccessException {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        cameraId = getFrontFacingCameraId(cameraManager);
        Log.d("FaceRecognition","camera:"+cameraId);
        CameraCharacteristics cc = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap streamConfigs = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        setImageReaders(streamConfigs);
        openCamera2(cameraManager, cameraId);
    }

    private void setImageReaders(StreamConfigurationMap streamConfigs){
        rawSizes = streamConfigs.getOutputSizes(ImageFormat.RAW_SENSOR);
        jpegSizes = streamConfigs.getOutputSizes(ImageFormat.JPEG);

        //rawImageReader = ImageReader.newInstance(rawSizes[0].getWidth(),rawSizes[0].getHeight(), ImageFormat.RAW12, 1);
        jpegImageReader = ImageReader.newInstance(jpegSizes[0].getWidth(), jpegSizes[0].getHeight(), ImageFormat.JPEG, 1);

/*        rawImageReader.setOnImageAvailableListener(new OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader imageReader) {
                Log.d("FaceRecognition","Raw image available");
            }
        }, mBackgroundHandler);*/

        jpegImageReader.setOnImageAvailableListener(new OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                // save jpeg
                Log.d("FaceRecognition","JPEG image available");
                try {
                    Image img = reader.acquireNextImage();
                    FirebaseVisionImageMetadata metadata = new FirebaseVisionImageMetadata.Builder()
                            .setWidth(640)
                            .setHeight(480)
                            .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21)
                            .setRotation(getRotationCompensation(cameraId,FaceRecognitionActivity.this,FaceRecognitionActivity.this))
                            .build();
                    FirebaseVisionImage image = FirebaseVisionImage.fromByteBuffer(img.getPlanes()[0].getBuffer(),metadata);
                    Log.d(TAG,"size:"+image.toString()+" "+img.getWidth()+" "+img.getHeight());
                    reader.close();
                    img = null;
                    FirebaseVisionFaceDetector detector = FirebaseVision.getInstance()
                            .getVisionFaceDetector(options);

                    detectFaces(image,detector);

                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }
        }, mBackgroundHandler);

        //rawCaptureSurface = rawImageReader.getSurface();
        jpegCaptureSurface = jpegImageReader.getSurface();
    }

    private void detectFaces(FirebaseVisionImage image, FirebaseVisionFaceDetector detector) {
        Task<List<FirebaseVisionFace>> result =
                detector.detectInImage(image)
                        .addOnSuccessListener(
                                new OnSuccessListener<List<FirebaseVisionFace>>() {
                                    @Override
                                    public void onSuccess(List<FirebaseVisionFace> faces) {
                                        // Task completed successfully
                                        // ...
                                        checkFaces(faces);
                                    }
                                })
                        .addOnFailureListener(
                                new OnFailureListener() {
                                    @Override
                                    public void onFailure(@NonNull Exception e) {
                                        // Task failed with an exception
                                        // ...
                                    }
                                });
    }

    private void checkFaces(List<FirebaseVisionFace> faces) {
        for (FirebaseVisionFace face : faces) {
            Rect bounds = face.getBoundingBox();
            float rotY = face.getHeadEulerAngleY();  // Head is rotated to the right rotY degrees
            float rotZ = face.getHeadEulerAngleZ();  // Head is tilted sideways rotZ degrees

            // If landmark detection was enabled (mouth, ears, eyes, cheeks, and
            // nose available):
            FirebaseVisionFaceLandmark leftEar = face.getLandmark(FirebaseVisionFaceLandmark.LEFT_EAR);
            if (leftEar != null) {
                FirebaseVisionPoint leftEarPos = leftEar.getPosition();
            }

            // If classification was enabled:
            if (face.getSmilingProbability() != FirebaseVisionFace.UNCOMPUTED_PROBABILITY) {
                float smileProb = face.getSmilingProbability();
            }
            if (face.getRightEyeOpenProbability() != FirebaseVisionFace.UNCOMPUTED_PROBABILITY) {
                float rightEyeOpenProb = face.getRightEyeOpenProbability();
            }

            // If face tracking was enabled:
            if (face.getTrackingId() != FirebaseVisionFace.INVALID_ID) {
                int id = face.getTrackingId();
            }
            Log.d(TAG,bounds.toString());
            //tryDrawing(preview,bounds);
        }


    }

    private void checkFaces2(Face[] faces) {
        Log.d(TAG,"faces:"+faces.length);
        for (Face face : faces) {
            Rect bounds = face.getBounds();

            // If landmark detection was enabled (mouth, ears, eyes, cheeks, and
            // nose available):

            Log.d(TAG,bounds.toString());
            tryDrawing(preview,bounds);
        }


    }

    private void tryDrawing(TextureView holder,Rect rect) {
        Log.i(TAG, "Trying to draw...");

        Canvas canvas = holder.lockCanvas();
        if (canvas == null) {
            Log.e(TAG, "Cannot draw onto the canvas as it's null");
        } else {
            drawMyStuff(canvas,rect);
            holder.unlockCanvasAndPost(canvas);
        }
    }

    private void drawMyStuff(final Canvas canvas, Rect rect) {
        Paint p = new Paint();
        p.setColor(Color.BLACK);
        canvas.drawRect(rect,p);
        Log.i(TAG, "Drawing...");
        canvas.drawRGB(255, 128, 128);
    }


    /**
     * Get the angle by which an image must be rotated given the device's current
     * orientation.
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private int getRotationCompensation(String cameraId, Activity activity, Context context)
            throws CameraAccessException {
        // Get the device's current rotation relative to its "native" orientation.
        // Then, from the ORIENTATIONS table, look up the angle the image must be
        // rotated to compensate for the device's rotation.
        int deviceRotation = getWindowManager().getDefaultDisplay().getRotation();
        int rotationCompensation = ORIENTATIONS.get(deviceRotation);

        // On most devices, the sensor orientation is 90 degrees, but for some
        // devices it is 270 degrees. For devices with a sensor orientation of
        // 270, rotate the image an additional 180 ((270 + 270) % 360) degrees.
        CameraManager cameraManager = (CameraManager) context.getSystemService(CAMERA_SERVICE);
        int sensorOrientation = cameraManager
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION);
        rotationCompensation = (rotationCompensation + sensorOrientation + 270) % 360;

        // Return the corresponding FirebaseVisionImageMetadata rotation value.
        int result;
        switch (rotationCompensation) {
            case 0:
                result = FirebaseVisionImageMetadata.ROTATION_0;
                break;
            case 90:
                result = FirebaseVisionImageMetadata.ROTATION_90;
                break;
            case 180:
                result = FirebaseVisionImageMetadata.ROTATION_180;
                break;
            case 270:
                result = FirebaseVisionImageMetadata.ROTATION_270;
                break;
            default:
                result = FirebaseVisionImageMetadata.ROTATION_0;
                Log.e(TAG, "Bad rotation value: " + rotationCompensation);
        }
        return result;
    }


    private void openCamera2(CameraManager cameraManager, String cameraId) throws CameraAccessException {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice cameraDevice) {
                mCamera = cameraDevice;
                try {
                    setCaptureSession(mCamera);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice cameraDevice) {

            }

            @Override
            public void onError(@NonNull CameraDevice cameraDevice, int i) {

            }
        }, null);
    }

    private void setCaptureSession(CameraDevice mCamera) throws CameraAccessException {
        List<Surface> surfaces = Arrays.asList(previewSurface);//, jpegCaptureSurface);//, rawCaptureSurface);
        mCamera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                mSession = session;
                // to next step...
                try {
                    sendRequest();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {

            }

        }, mBackgroundHandler);
    }

    private void sendRequest() throws CameraAccessException {
        CaptureRequest.Builder request = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        request.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL);
        request.addTarget(previewSurface);
        //request.addTarget(jpegCaptureSurface);


// set capture options: fine-tune manual focus, white balance, etc.

        mSession.setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                // updated values can be found here
                for(CaptureResult res :result.getPartialResults()){
                    checkFaces2(res.get(CaptureResult.STATISTICS_FACES));
                }
                    //sendImageRequest();


            }
        }, mBackgroundHandler);
    }

    private void creatCaptureSession(final CaptureRequest.Builder captureBuilder) throws CameraAccessException {
        mCamera.createCaptureSession(Arrays.asList(rawImageReader.getSurface()), new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                try {
                    session.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
            }
        }, mBackgroundHandler);
    }

    private void sendImageRequest() throws CameraAccessException {

        rawImageReader.setOnImageAvailableListener(new OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader imageReader) {
                Log.d("FaceRecognition","Raw image available");
            }
        }, mBackgroundHandler);

        CaptureRequest.Builder captureBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captureBuilder.addTarget(rawImageReader.getSurface());
        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        // Orientation
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

        creatCaptureSession(captureBuilder);
    }

    Surface previewSurface;
    Surface rawCaptureSurface;
    Surface jpegCaptureSurface;

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (preview.isAvailable()) {
            //openCamera(preview.getWidth(), preview.getHeight());
            //openCamera2();
        } else {
            preview.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        //try {
        //mCameraOpenCloseLock.acquire();
        if (null != mSession) {
            mSession.close();
            mSession = null;
        }
        if (null != mCamera) {
            mCamera.close();
            mCamera = null;
        }

        if (null != jpegImageReader) {
            jpegImageReader.close();
            jpegImageReader = null;
        }

        if (null != rawImageReader) {
            rawImageReader.close();
            rawImageReader = null;
        }

        /*}
        catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            //mCameraOpenCloseLock.release();
        }*/
    }

    private String getFrontFacingCameraId(CameraManager cManager){
        try {
            for(final String cameraId : cManager.getCameraIdList()){
                CameraCharacteristics characteristics = cManager.getCameraCharacteristics(cameraId);
                int cOrientation = characteristics.get(CameraCharacteristics.LENS_FACING);
                if(cOrientation == CameraCharacteristics.LENS_FACING_FRONT) return cameraId;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return null;
    }


    CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            Log.d("FaceRecognition","capture listener");
        }
    };

}
