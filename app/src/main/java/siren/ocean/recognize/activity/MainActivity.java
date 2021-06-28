package siren.ocean.recognize.activity;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import pub.devrel.easypermissions.EasyPermissions;
import siren.ocean.recognize.AppContext;
import siren.ocean.recognize.FaceRecognize;
import siren.ocean.recognize.R;
import siren.ocean.recognize.entity.CameraParameter;
import siren.ocean.recognize.util.CommonUtil;
import siren.ocean.recognize.util.PhotoUtils;
import siren.ocean.recognize.util.PreferencesUtility;
import siren.ocean.recognize.util.SimilarUtil;
import siren.ocean.recognize.util.ThreadUtil;
import siren.ocean.recognize.widget.CameraView;
import siren.ocean.yuv.YuvUtil;

/**
 * 主页
 * Created by Siren on 2021/6/17.
 */
public class MainActivity extends AppCompatActivity implements EasyPermissions.PermissionCallbacks {

    private CameraView mCameraView;
    private SurfaceHolder mSurfaceHolder;
    private ImageView ivPhoto;
    private TextView tvResult;
    private final List<String> resolutionData = new ArrayList<>(Arrays.asList("640X480", "1280X720", "1280X960"));
    private final List<Integer> anglesData = new ArrayList<>(Arrays.asList(0, 90, 180, 270));
    private final List<Boolean> mirrorData = new ArrayList<>(Arrays.asList(true, false));
    private final CameraParameter parameter = PreferencesUtility.getCameraParameter();
    private final ExecutorService detectThread = ThreadUtil.getSingleThreadExecutor();//基于检测的单线程策略,尽可能实时绘制人脸框
    private final ExecutorService recognizeThread = ThreadUtil.getSingleThreadExecutor();//基于识别的单线程策略，耗时操作，同时需要计算相似度并匹配结果，独立出来的线程
    private final Map<String, float[]> memoryMap = new HashMap<>();//模拟内存管理
    private float ratio;
    private LinearLayout llGesture;
    private BottomSheetBehavior<LinearLayout> mSheetBehavior;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
        initPreview();
        initCameraParameter();
        requestPermission();
        FaceRecognize.getInstance().initModels(getAssets());
        ratio = calculateBiasRatio(parameter);
    }

    private void initView() {
        tvResult = findViewById(R.id.tv_result);
        ivPhoto = findViewById(R.id.iv_photo);
        mCameraView = findViewById(R.id.view_camera);
        mSheetBehavior = BottomSheetBehavior.from(findViewById(R.id.include_bottom_sheet));
        mSheetBehavior.setHideable(false);
        llGesture = findViewById(R.id.ll_gesture);
        llGesture.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                llGesture.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                mSheetBehavior.setPeekHeight(llGesture.getMeasuredHeight());
            }
        });

        SurfaceView surfaceView = findViewById(R.id.view_surface);
        surfaceView.setZOrderOnTop(true);
        mSurfaceHolder = surfaceView.getHolder();
        mSurfaceHolder.setFormat(PixelFormat.TRANSPARENT);
    }

    private void initCameraParameter() {
        initCameraId();
        initResolution();
        initOrientation();
        initRotation();
        initMirror();
    }

    private void initPreview() {
        mCameraView.setParameter(parameter.getCameraId(), parameter.getResolution(), parameter.getOrientation());
        mCameraView.setPreviewCallback((data, camera) -> {
            mCameraView.addCallbackBuffer();
            byte[] imageData = YuvUtil.nv21RotateMirror(data, parameter.getResolution()[0], parameter.getResolution()[1], parameter.getRotation(), parameter.isMirror(), 1);
            int w, h;
            //如果流数据做了直角旋转，则必然导致宽高互换
            if (parameter.getRotation() == 90 || parameter.getRotation() == 270) {
                w = mCameraView.mPreviewHeight;
                h = mCameraView.mPreviewWidth;
            } else {
                w = mCameraView.mPreviewWidth;
                h = mCameraView.mPreviewHeight;
            }

            detectFace(imageData, w, h);
            ivPhoto.setImageBitmap(PhotoUtils.nv21ToBitmap(this, imageData, w, h));
        });
    }

    private void detectFace(byte[] imageData, int w, int h) {
        detectThread.submit(() -> {
            int[] faceInfo = FaceRecognize.getInstance().detectFace(imageData, w, h, FaceRecognize.IMAGE_TYPE_NV21);
            if (faceInfo == null || faceInfo.length == 1) {
                surfaceDraw(null);
                showResult("", "");
                return;
            }
            surfaceDraw(faceInfo);
            featureMatch(imageData, w, h, faceInfo);
        });
    }

    private void surfaceDraw(int[] faceInfo) {
        PhotoUtils.surfaceDraw(mSurfaceHolder, faceInfo, ratio);
    }

    private void featureMatch(byte[] imageData, int w, int h, int[] faceInfo) {
        recognizeThread.submit(() -> {
            if (memoryMap.size() > 0) {
                float[] feature = FaceRecognize.getInstance().featureExtract(imageData, w, h, faceInfo, FaceRecognize.IMAGE_TYPE_NV21);
                float[] result = SimilarUtil.distanceArray(feature, getMemoryFeatures());
                int index = (int) result[0];
                String name = getNameByIndex(index);
                showResult(name, String.valueOf(result[1]));
            }
        });
    }

    /**
     * 显示最相似结果和相似度
     */
    private void showResult(String name, String similarity) {
        runOnUiThread(() -> tvResult.setText(name + "\n" + similarity));
    }

    private float[][] getMemoryFeatures() {
        if (memoryMap.size() == 0) {
            return null;
        }

        int len = memoryMap.size();
        float[][] fea = new float[len][128];
        int i = 0;
        for (Map.Entry<String, float[]> entry : memoryMap.entrySet()) {
            fea[i++] = entry.getValue();
        }
        return fea;
    }

    private String getNameByIndex(int index) {
        Iterator<Map.Entry<String, float[]>> iterator = memoryMap.entrySet().iterator();
        for (int i = 0; i < index; i++) {
            iterator.next();
        }
        return iterator.next().getKey();
    }

    /**
     * 此处通过TextureView获取bitmap的功能测试不同图片数据类型的处理
     */
    public void takePicture(View view) {
        Bitmap sourceBitmap = mCameraView.getBitmap();
        int width = sourceBitmap.getWidth();
        int height = sourceBitmap.getHeight();
        byte[] imageData = PhotoUtils.getPixelsRGBA(sourceBitmap);
        int[] faceInfo = FaceRecognize.getInstance().detectFace(imageData, width, height, FaceRecognize.IMAGE_TYPE_RGBA);
        if (faceInfo != null && faceInfo.length > 1) {
            float[] feature = FaceRecognize.getInstance().featureExtract(imageData, width, height, faceInfo, FaceRecognize.IMAGE_TYPE_RGBA);
            Bitmap avatar = PhotoUtils.getAvatar(sourceBitmap, faceInfo);
            showDialog(avatar, feature);
        } else {
            Toast.makeText(this, "未检测到人脸", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDialog(Bitmap avatar, float[] feature) {
        EditText editText = new EditText(this);
        editText.setSingleLine();
        ImageView imageView = new ImageView(this);
        imageView.setImageBitmap(avatar);
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setPadding(50, 50, 50, 50);
        linearLayout.addView(imageView);
        linearLayout.addView(editText);

        new AlertDialog.Builder(this)
                .setTitle("请输入姓名")
                .setView(linearLayout)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    String name = editText.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) {
                        Toast.makeText(this, "姓名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    memoryMap.put(name, feature);
                }).show();
    }

    private void initCameraId() {
        ArrayAdapter<Integer> adapter = new ArrayAdapter<>(this, R.layout.support_simple_spinner_dropdown_item, android.R.id.text1, CommonUtil.getCameraIds());
        Spinner spinner = findViewById(R.id.sp_camera_id);
        spinner.setAdapter(adapter);
        spinner.setSelection(parameter.getCameraId());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
                int cameraId = adapter.getItem(position);
                if (parameter.getCameraId() == cameraId) return;
                parameter.setCameraId(cameraId);
                updateParameter();
            }

            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });
    }

    private void initResolution() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.support_simple_spinner_dropdown_item, android.R.id.text1, resolutionData);
        Spinner spinner = findViewById(R.id.sp_resolution);
        spinner.setAdapter(adapter);
        int[] data = parameter.getResolution();
        spinner.setSelection(resolutionData.indexOf(data[0] + "X" + data[1]));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
                String[] data = adapter.getItem(position).split("X");
                int[] resolution = new int[]{Integer.parseInt(data[0]), Integer.parseInt(data[1])};
                if (Arrays.equals(parameter.getResolution(), resolution)) return;
                parameter.setResolution(resolution);
                ratio = calculateBiasRatio(parameter);
                updateParameter();
            }

            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });
    }

    private void initOrientation() {
        ArrayAdapter<Integer> adapter = new ArrayAdapter<>(this, R.layout.support_simple_spinner_dropdown_item, android.R.id.text1, anglesData);
        Spinner spinner = findViewById(R.id.sp_orientation);
        spinner.setAdapter(adapter);
        spinner.setSelection(anglesData.indexOf(parameter.getOrientation()));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
                int orientation = adapter.getItem(position);
                if (parameter.getOrientation() == orientation) return;
                parameter.setOrientation(adapter.getItem(position));
                updateParameter();
            }

            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });
    }

    private void initRotation() {
        ArrayAdapter<Integer> adapter = new ArrayAdapter<>(this, R.layout.support_simple_spinner_dropdown_item, android.R.id.text1, anglesData);
        Spinner spinner = findViewById(R.id.sp_rotation);
        spinner.setAdapter(adapter);
        spinner.setSelection(anglesData.indexOf(parameter.getRotation()));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
                int rotation = adapter.getItem(position);
                if (parameter.getRotation() == rotation) return;
                parameter.setRotation(adapter.getItem(position));
                updateParameter();
            }

            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });
    }

    private void initMirror() {
        ArrayAdapter<Boolean> adapter = new ArrayAdapter<>(this, R.layout.support_simple_spinner_dropdown_item, android.R.id.text1, mirrorData);
        Spinner spinner = findViewById(R.id.sp_mirror);
        spinner.setAdapter(adapter);
        spinner.setSelection(mirrorData.indexOf(parameter.isMirror()));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
                boolean isMirror = adapter.getItem(position);
                if (parameter.isMirror() == isMirror) return;
                parameter.setMirror(isMirror);
                updateParameter();
            }

            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });
    }

    private void requestPermission() {
        String[] perms = {Manifest.permission.CAMERA};
        if (EasyPermissions.hasPermissions(this, perms)) {
            ThreadUtil.runOnMainThreadDelayed(() -> mCameraView.openCamera(), 300);
        } else {
            EasyPermissions.requestPermissions(this, "The app must have the permission of cameras", 0, perms);
        }
    }

    @Override
    public void onPermissionsGranted(int requestCode, List<String> perms) {
        mCameraView.openCamera();
    }

    @Override
    public void onPermissionsDenied(int requestCode, List<String> perms) {
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    protected void onResume() {
        mCameraView.openCamera();
        super.onResume();
    }

    @Override
    protected void onPause() {
        mCameraView.releaseCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        detectThread.shutdown();
        recognizeThread.shutdown();
        FaceRecognize.getInstance().faceDeInit();
    }

    private void updateParameter() {
        PreferencesUtility.setCameraParameter(parameter);
        mCameraView.setParameter(parameter.getCameraId(), parameter.getResolution(), parameter.getOrientation());
    }

    private float calculateBiasRatio(CameraParameter parameter) {
        int width = CommonUtil.getScreenWidth(AppContext.get());
        int height = CommonUtil.getScreenHeight(AppContext.get());
        float bias = width > height ? (width / (float) parameter.getResolution()[0]) : (width / (float) parameter.getResolution()[1]);
        DecimalFormat format = new DecimalFormat(".0000");
        return Float.parseFloat(format.format(bias));
    }
}