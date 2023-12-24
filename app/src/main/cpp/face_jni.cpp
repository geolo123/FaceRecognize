#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <jni.h>
#include <string>
#include <vector>
#include <malloc.h>
#include "log.h"
#include "recognize.h"
#include "mtcnn.h"
#include "utils.h"

using namespace std;
MTCNN *mtcnn;
Recognize *recognize;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_siren_ocean_recognize_FaceRecognize_initModels(JNIEnv *env, jobject instance,
                                                    jobject assetManager) {

    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    mtcnn = new MTCNN(mgr);
    recognize = new Recognize(mgr);
    LOGD("init models success");
    return (jboolean) true;
}

JNIEXPORT jintArray JNICALL
Java_siren_ocean_recognize_FaceRecognize_faceDetect(JNIEnv *env,
                                                    jobject instance,
                                                    jbyteArray imageData_,
                                                    jint imageWidth,
                                                    jint imageHeight,
                                                    jint imageType) {
    /*
     * 建议在C++11及以上版本中使用nullptr。
     * nullptr是C++11引入的关键字，用于表示空指针，其含义与NULL和0不同。
     * NULL和0在C++中被广泛使用表示空指针，但在一些情况下会产生二义性（例如在重载函数时）。
     * 而 nullptr 仅能被赋值给指针类型，不具有整数类型，能够减少由NULL和0引起的问题。
     */

    /*
     * GetByteArrayElements 用于获取数组内容，直到ReleaseByteArrayElements()被调用。
     * 意思就是在ReleaseByteArrayElements被调用之前 这个数据一直有效。
     * 所以使用 getByteArrayElements 就必须使用 releaseByteArrayElements，否则会造成内存泄漏。
     */
    jbyte *imageData = env->GetByteArrayElements(imageData_, nullptr);
    if (nullptr == imageData) {
        env->ReleaseByteArrayElements(imageData_, imageData, 0);
        return nullptr;
    }

    //根据不同数据类型转 ncnn::Mat
    ncnn::Mat ncnn_img = jniutils::formatMat((unsigned char *) imageData, imageWidth, imageHeight, imageType);
    if (ncnn_img.data == nullptr) {
        env->ReleaseByteArrayElements(imageData_, imageData, 0);
        return nullptr;
    }

    /*
     * mtcnn 检测
     * 模型用到mtcnn，检测最大人脸，提升检测速度
     *
     * static_cast、dynamic_cast、const_cast和reinterpret_cast（四种类型转换运算符）
     *
     * static_cast: 用于良性转换，一般不会导致意外发生，风险很低。
     * const_cast: 用于 const 与非 const、volatile 与非 volatile 之间的转换。
     * reinterpret_cast: 高度危险的转换，这种转换仅仅是对二进制位的重新解释，不会借助已有的转换规则对数据进行调整，但是可以实现最灵活的 C++ 类型转换。
     * dynamic_cast: 借助 RTTI，用于类型安全的向下转型（Downcasting）。
     * <code>
     *   double scores = 95.5;
     *   int n = static_cast<int>(scores);
     * </code>
     *
     *
     */
    std::vector<Bbox> finalBbox;
    mtcnn->detectMaxFace(ncnn_img, finalBbox);
    auto num_face = static_cast<int32_t>(finalBbox.size());
    int out_size = 1 + num_face * 14; // 这个14，是有识别框的4个坐标点 + 人脸关键点的10个坐标点 组成；
    int *faceInfo = new int[out_size];
    faceInfo[0] = num_face;
    for (int i = 0; i < num_face; i++) {
        faceInfo[14 * i + 1] = finalBbox[i].x1;//left
        faceInfo[14 * i + 2] = finalBbox[i].y1;//top
        faceInfo[14 * i + 3] = finalBbox[i].x2;//right
        faceInfo[14 * i + 4] = finalBbox[i].y2;//bottom
        for (int j = 0; j < 10; j++) { // 5个关键点的位置
            faceInfo[14 * i + 5 + j] = static_cast<int>(finalBbox[i].ppoint[j]);
        }
    }

    jintArray tFaceInfo = env->NewIntArray(out_size);
    env->SetIntArrayRegion(tFaceInfo, 0, out_size, faceInfo);
    // 最后要释放手机图片的资源池
    env->ReleaseByteArrayElements(imageData_, imageData, 0);
    return tFaceInfo;
}

JNIEXPORT jfloatArray JNICALL
Java_siren_ocean_recognize_FaceRecognize_extractFeature(JNIEnv *env, jobject instance,
                                                        jbyteArray imageData_,
                                                        jint imageWidth,
                                                        jint imageHeight,
                                                        jintArray point_,
                                                        jint imageType) {

    jint *point = env->GetIntArrayElements(point_, 0);
    jbyte *imageData = env->GetByteArrayElements(imageData_, nullptr);
    if (nullptr == imageData) {
        env->ReleaseByteArrayElements(imageData_, imageData, 0);
        return nullptr;
    }

    //根据不同数据类型转ncnn::Mat
    ncnn::Mat ncnn_img = jniutils::formatMat((unsigned char *) imageData, imageWidth, imageHeight, imageType);
    if (ncnn_img.data == nullptr) {
        env->ReleaseByteArrayElements(imageData_, imageData, 0);
        return nullptr;
    }

    ncnn::Mat img = recognize->preprocess(ncnn_img, (int *) point);
    int size = 128;
    auto *feature = new float[size];
    recognize->start(img, feature);
    jfloatArray featureArray = env->NewFloatArray(size);
    env->SetFloatArrayRegion(featureArray, 0, size, feature);
    env->ReleaseByteArrayElements(imageData_, imageData, 0);
    env->ReleaseIntArrayElements(point_, point, 0);
    return featureArray;
}

JNIEXPORT jboolean JNICALL
Java_siren_ocean_recognize_FaceRecognize_faceDeInit(JNIEnv *env, jobject instance) {
    delete mtcnn;
    delete recognize;
    LOGD("faceDeInit release success");
    return (jboolean) true;
}
}