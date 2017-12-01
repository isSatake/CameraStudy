package com.stk.camerastudy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private static final int PERMISSION_REQUEST_CODE = 0;

    private CameraDevice camera;

    private CameraManager manager;

    private CameraCaptureSession captureSession;

    private CaptureRequest previewRequest;

    private CaptureRequest stillRequest;

    private String selectedCameraId;

    private LinearLayout main;

    private TextureView textureView;

    private ImageReader imageReader;

    private Handler backgroundHandler = new Handler();

    private boolean isPreviewing = false;

    private boolean isProcessing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestAppPermissions();
        setContentView(R.layout.activity_main);

        main = findViewById(R.id.main);

        //カメラプレビュー用TextureViewの設定
        //TODO TextureViewって何
        textureView = findViewById(R.id.texture); //View取得
        textureView.setSurfaceTextureListener(previewSurfaceTextureListener); //View準備完了コールバック

        //静止画撮影用ImageReaderの設定
        //Viewではない
        imageReader = ImageReader.newInstance(960, 720, ImageFormat.JPEG, 2); //ImageReader初期化
        imageReader.setOnImageAvailableListener(stillImageReaderAvailableListener, backgroundHandler); //ImageReader準備完了コールバック

        Button captureButton = findViewById(R.id.button_capture);
        captureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isPreviewing) {
                    stopPreview();
                } else {
                    startPreview();
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        //アプリポーズ時にリリースしないと他のアプリがカメラを使えない
        closeCamera(camera);
    }

    //カメラと接続
    @SuppressLint("MissingPermission")
    private void openCamera() {
        Log.d(TAG, "openCamera");
        manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            selectedCameraId = manager.getCameraIdList()[0]; //利用可能なカメラデバイスを取得 (メインカメラ・インカメラなど)
            manager.openCamera(selectedCameraId, cameraStateCallback, null); //使うカメラをオープン
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //カメラから画像を取得する
    //この手続をCameraPreviewSessionと呼んでいる
    private void createCameraPreviewSession() {
        Log.d(TAG, "createCameraPreviewSession");

//        try {
//            camera.createCaptureSession(Collections.singletonList(imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
//                @Override
//                public void onConfigured(CameraCaptureSession session) {
//                    Log.d(TAG, "capture session onConfigured");
//                    // カメラがcloseされている場合
//                    if (null == camera) {
//                        return;
//                    }
//                    try {
//                        captureSession = session;
//                        captureSession.capture(finalCaptureBuilder.build(), null, null); //ImageReader.OnImageAvailableに結果がくる
//                    } catch (CameraAccessException e) {
//                        e.printStackTrace();
//                    }
//                }
//
//                @Override
//                public void onConfigureFailed(CameraCaptureSession session) {
//                    Log.d(TAG, "capture session onConfigureFailed");
//                }
//            }, null);
//        } catch (CameraAccessException e) {
//            e.printStackTrace();
//        }

        //カメラ画像を流すSurfaceを設定(複数可)
        List<Surface> outputs = Arrays.asList(getSurfaceFromTexture(textureView), imageReader.getSurface()); //各リクエストで出力先を設定してるのになぜここでも必要なのか
        previewRequest = makePreviewRequest();
        stillRequest = makeStillCaptureRequest();

        //カメラに画像をくれと言う
        //プレビュー用、撮影用といった複数の出力先(Surface)と、状態遷移(AF,AE,撮影,etc)のコールバックを渡す
        //このsessionは、別のsessionをattachする/cameraがcloseされる まで生き続ける
        //急にcloseされても、作業途中のsessionはちゃんと完了される
        try {
            camera.createCaptureSession(outputs, captureSessionCallBack, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //静止画撮影用リクエストを作成
    private CaptureRequest makeStillCaptureRequest() {
        CaptureRequest.Builder stillCaptureRequestBuilder = null;
        try {
            stillCaptureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        stillCaptureRequestBuilder.addTarget(imageReader.getSurface());
        stillCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_TRIGGER_START);
        stillCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        return stillCaptureRequestBuilder.build();
    }

    //撮影前のプレビュー用リクエストを作成
    private CaptureRequest makePreviewRequest() {
        CaptureRequest.Builder previewRequestBuilder = null;
        try {
            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        previewRequestBuilder.addTarget(getSurfaceFromTexture(textureView)); //カメラ画像を流すSurfaceをセット
//        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO); //プレビュー中は自動AF
//        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_TRIGGER_START);
//        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        return previewRequestBuilder.build(); //リクエスト完成
    }

    private Surface getSurfaceFromTexture(TextureView textureView) {
        SurfaceTexture texture = textureView.getSurfaceTexture();
        texture.setDefaultBufferSize(960, 720);
        return new Surface(texture);
    }

    //CaptureSessionの状態遷移コールバック
    private CameraCaptureSession.StateCallback captureSessionCallBack = new CameraCaptureSession.StateCallback() {
        //カメラの設定が完了した (最初の1回だけ)
        @Override
        public void onConfigured(CameraCaptureSession session) {
            Log.d(TAG, "capture session onConfigured");
            if (null == camera) {
                return;
            }

            captureSession = session;
            startPreview();
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
        }
    };

    //撮影前プレビュー用リクエストを投げる
    //setRepeatingRequest→30FPSでカメラ画像を送ってくれる→targetとして設定したtextureViewにカメラ画像が見える
    private void startPreview() {
        Log.d(TAG, "Start preview");
        try {
            captureSession.setRepeatingRequest(previewRequest, previewCallback, null);
            isPreviewing = true;
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void stopPreview() {
        Log.d(TAG, "Stop preview");
        try {
            captureSession.stopRepeating();
            isPreviewing = false;
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //撮影用リクエストを投げる
    private void startCapture() {
        Log.d(TAG, "Start capture");
        try {
            isProcessing = true;
            stopPreview();
            //1回しかリクエストできない→1フレームしか画像を取れない→AF/AEしてる暇がない→AF/AEはsetRepeatingRequestでやらないといけない
            captureSession.capture(stillRequest, stillCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //プレビューリクエストのコールバック
    //画像はViewで受け取るので渡ってこない
    private final CameraCaptureSession.CaptureCallback previewCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {
        }

        //stopRepeatingしても数回発火してしまう
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
//            Log.d(TAG, result.get(CaptureResult.CONTROL_AF_STATE) + "");
            if (!isProcessing && result.get(CaptureResult.CONTROL_AF_STATE).equals(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED)) {
                main.setBackgroundColor(0xff99cc00);
                startCapture();
            } else {
                main.setBackgroundColor(0xfff);
            }
        }
    };

    //撮影リクエストのコールバック
    private final CameraCaptureSession.CaptureCallback stillCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            Log.d(TAG, "onStillCaptureCompleted");
        }
    };

    //OSのファイルDBに写真を登録
    //フォトアプリなどにすぐ反映される
    private void registerDatabase(String file) {
        ContentValues contentValues = new ContentValues();
        ContentResolver contentResolver = MainActivity.this.getContentResolver();
        contentValues.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        contentValues.put("_data", file);
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
    }

    //カメラリリース処理
    //なぜimageReaderも閉じてるんだっけ
    private void closeCamera(CameraDevice c) {
        if (c != null) {
            Log.d(TAG, "close camera");
            c.close();
            camera = null;
        } else {
            Log.d(TAG, "camera is null");
        }
        if (imageReader != null) {
            Log.d(TAG, "close imageReader");
            imageReader.close();
            imageReader = null;
        }
    }

    //プレビュー用Surface準備完了コールバック
    TextureView.SurfaceTextureListener previewSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
            openCamera();
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

    //静止画撮影用ImageReader準備完了コールバック
    ImageReader.OnImageAvailableListener stillImageReaderAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.d(TAG, "onImageAvailable");
            //TODO 結果をtextureViewに流したい
            //imagereaderからバイト列を取り出す
            Image image;
            image = reader.acquireLatestImage(); //2回以上取り出そうとするとIllegalStateException
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.capacity()];
            buf.get(bytes);
            //そのままfileに書き込み→JPEG
            saveByteToFile(bytes);
            //BitmapFactoryに流す→View
        }

        private void saveByteToFile(byte[] bytes) {
            String timeStamp = new SimpleDateFormat("yyyyMMddHHmmss", Locale.JAPAN).format(new Date());
            String imageFileName = "JPEG-" + timeStamp;

            //getExternalStoragePublicDirectory -> 外部アプリから見えるディレクトリ
            //DIRECTORY_PICTURESにディレクトリを作ると、フォトアプリ「端末内の写真」でディレクトリが見える
            File storageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CameraStudy");
            storageDir.mkdirs();
            File file = null;
            OutputStream outputStream = null;

            try {
                file = File.createTempFile(imageFileName, ".jpg", storageDir);
                outputStream = new FileOutputStream(file);
                outputStream.write(bytes);
            } catch (IOException e) {
                //TODO 再試行するとか
                Log.d(TAG, "Failed to save jpg file");
                e.printStackTrace();
            } finally {
                //TODO try-with-resources
                if (outputStream != null) {
                    try {
                        Log.d(TAG, "Succeeded to save jpg file");
                        Log.d(TAG, file.getAbsolutePath());
                        registerDatabase(file.getAbsolutePath());
                        outputStream.close();
                    } catch (IOException e) {
                        Log.d(TAG, "Failed to close stream");
                        e.printStackTrace();
                    }
                }
            }
        }
    };

    //カメラ接続コールバック
    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cameraDevice) {
            Log.d(TAG, "camera onOpened");
            camera = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            Log.d(TAG, "camera onDisconnected");
            closeCamera(cameraDevice);
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            Log.d(TAG, "camera onError");
            closeCamera(cameraDevice);
        }
    };

    private void requestAppPermissions() {
        //TODO permissionのlintが欲しい(スペルミスしてても例外出ないし気付けない)
        if (checkSelfPermission("android.permission.CAMERA") != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    "android.permission.CAMERA",
                    "android.permission.WRITE_EXTERNAL_STORAGE"
            }, PERMISSION_REQUEST_CODE);
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_CODE: {
                if (grantResults.length > 0) {
                    for (int result : grantResults) {
                        if (result == 0) {
                            requestAppPermissions(); //権限をくれるまで要求する
                        }
                    }
                }
            }
        }
    }
}
