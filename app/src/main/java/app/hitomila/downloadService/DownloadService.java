package app.hitomila.downloadService;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;

import java.io.File;
import java.util.HashMap;
import java.util.regex.Matcher;

import app.hitomila.R;
import app.hitomila.common.exception.CrashlyticsLoggingException;
import app.hitomila.common.exception.htmlParsingException;
import app.hitomila.common.hitomiObjects.HitomiDownloadingDataObject;
import app.hitomila.common.hitomiObjects.ReaderData;
import app.hitomila.main.IndexActivity;
import cz.msebera.android.httpclient.Header;

/**
 * Created by admin on 2016-11-02.
 */

public class DownloadService extends Service {
    //DataSet 은 GalleryNumber(for NotificationID), notification 을 가지며, 현재 진행중인 다운로드 알림들을 표시
    private HashMap<Integer, HitomiDownloadingDataObject> dataSet = new HashMap<>();
    private Notification.Builder mBuilder;
    private NotificationManager mNotificationManager;
    private Intent mIntent;

    //한번만 실행된다. 그 이후는 바로 onStartCommand.
    @Override
    public void onCreate() {
        super.onCreate();
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public int onStartCommand(Intent intent, int flags, final int startId) {
        if(intent == null){
            return super.onStartCommand(intent, flags, startId);
        }

        //stopself 삭제됨
        Crashlytics.log(3, "DownloadService::onStartCommand", intent.toString());

        Bundle bundle = intent.getExtras();
        mIntent = intent;
        final String plainGalleryUrl = bundle.getString("galleryUrl");
        final String readerUrl = DownloadServiceDataParser.galleryUrlToReaderUrl(plainGalleryUrl);

        Crashlytics.setString("readerUrl", readerUrl);
        new AsyncHttpClient().get(readerUrl, new AsyncHttpResponseHandler() {
            int galleryNumber;

            @Override
            public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
                galleryNumber = Integer.parseInt(DownloadServiceDataParser.extractGalleryNumberFromAddress(readerUrl));
                Crashlytics.setInt("galleryNumber", galleryNumber);

                ReaderData data = getReaderData(new String(responseBody));
                Crashlytics.setString("title", data.title);

                //TODO 20161113 DownloadService.this -> getApplicationContext()
                HitomiFileWriter fileWriter = new HitomiFileWriter(getApplicationContext(), data);
                Notification.Builder currNotification = initNotification(galleryNumber, fileWriter.getFilePath(), data.title, data.getImageCount());
                HitomiDownloadingDataObject item = new HitomiDownloadingDataObject(data, currNotification, galleryNumber);

                //재활용을 위해 해시에 넣어둔다.
                dataSet.put(galleryNumber, item);

                //정상적으로 정보를 수신한 후에는 FileWriter에 정보를 넘긴다
                //다운로드 큐를 돌리는것과 저장하는것은 FileWriter가 해준다.
                fileWriter.downloadAll(new DownloadNotifyCallback() {
                    HitomiDownloadingDataObject data = dataSet.get(galleryNumber);

                    @Override
                    public void notifyPageDownloaded() {
                        if(data == null) notifyDownloadFailed();

                        data.currentPage += 1;
                        data.notificationBuilder.setContentText(
                                data.currentPage + " / " + data.maxPages
                        );

                        mNotificationManager.notify(galleryNumber, data.notificationBuilder.build());
                    }

                    @Override
                    public void notifyDownloadCompleted() {
                        data.notificationBuilder.setContentText("다운로드 완료")
                                .setOngoing(false);
                        Toast.makeText(DownloadService.this, data.title + " 다운로드 완료" ,  Toast.LENGTH_SHORT).show();
                        mNotificationManager.notify(galleryNumber,data.notificationBuilder.build());
                        dataSet.remove(galleryNumber);
                        Crashlytics.log( 1, "DLService::DLCompleted", galleryNumber + ": Downloaded");
                        //stopSelf(startId);
                    }

                    @Override
                    public void notifyDownloadFailed() {
                        //TODO 에러 발생시 보통 imageWrite에서 나고있다. 왜 나는지 나중에 Log를 달아보자.
                        if(data == null){
                            Crashlytics.log( 1, "DLService::DLFailed", galleryNumber + ": FAILED");
                            Crashlytics.log("getData returns null");
                        } else{
                            Crashlytics.log( 1, "DLService::DLFailed", galleryNumber + ": FAILED");
                            Crashlytics.log(data.title);
                            data.notificationBuilder.setContentText("다운로드 실패, " + data.maxPages + "페이지 중 "
                                    + data.currentPage + "번째에서 오류 발생")
                                    .setOngoing(false);
                            mNotificationManager.notify(galleryNumber,data.notificationBuilder.build());
                        }


                        Crashlytics.logException(new CrashlyticsLoggingException(dataSet.get(galleryNumber)));
                        Toast.makeText(DownloadService.this, (data==null?"":data.title) + "다운로드 실패", Toast.LENGTH_SHORT).show();
                        dataSet.remove(galleryNumber);
                        //stopSelf(startId);
                    }
                });
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {
                //TODO 리더페이지에 접근하는 것 부터 실패했을 경우.
                Crashlytics.log("DownloadService::ReaderPage Load Failed");
                //stopSelf(startId);
            }
        });

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        //stopService(mIntent);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    //해당 망가의 진행상황을 확인시켜줄 노티피케이션의 초기화
    //노티피케이션의 고유 ID는 해당 작품의 번호로 한다.(동일 작품을 두번 받지 못하게 하려고)
    private Notification.Builder initNotification(int galleryNumber, String directoryName, String mangaTitle, int maxPages){
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        File root = new File(directoryName);
        Uri uri = Uri.fromFile(root);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "resource/folder");
//        intent.addCategory(Intent.CATEGORY_OPENABLE);
//        intent.setAction(Intent.ACTION_GET_CONTENT);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addParentStack(IndexActivity.class);
        //stackBuilder.addNextIntentWithParentStack(intent);
        stackBuilder.addNextIntent(intent);

        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(galleryNumber, PendingIntent.FLAG_UPDATE_CURRENT);

        mBuilder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.hitomi_32x32)
                .setAutoCancel(false)
                .setDefaults(Notification.DEFAULT_LIGHTS)
                .setPriority(Notification.PRIORITY_MAX)
                .setWhen(System.currentTimeMillis())
                //.setOngoing(true) 20161114 잠깐 삭제.
                //.setStyle(new Notification.BigTextStyle().bigText("BigText"))
                .setContentIntent(resultPendingIntent)
                .setContentTitle(mangaTitle)
                .setContentText("다운로드 대기중..");

        mNotificationManager.notify(galleryNumber, mBuilder.build());
        return mBuilder;
    }

    @Override
    public void onLowMemory() {
        Crashlytics.log("Service onLowMemory Logged");
        super.onLowMemory();
    }

    //이미 Javascript가 전부 실행된 후의 데이터이기 때문에 이미지출력 부분도 포함
    private ReaderData getReaderData(String html){
        String titleRegex = "<title>([^|]*)";
        String imageRegex = "(?:<div class=\"img-url\">)(.*)(?:<\\/div>)";

        String title;
        ReaderData resultData = null;
        try{
            Matcher matcher = DownloadServiceDataParser.getMatcher(titleRegex, html);
            //작품 타이틀을 찾는다. 이전 결과에서 다시 찾아올 수 있지만
            //결합성을 낮추기 위해 이렇게 해보았다.
            if(matcher.find())
                title = matcher.group(1);
            else throw new htmlParsingException("getReaderData", "titleMatching");
            title = title.trim();

            resultData = new ReaderData(title);

            //이미지 데이터는 카운팅, 파싱, 접두사 적용을 한번에 실시
            matcher = DownloadServiceDataParser.getMatcher(imageRegex, html);
            while(matcher.find()){
                String imageUrl = matcher.group(1).replaceFirst("(//[^\\.]*)","https://" + DownloadServiceDataParser.prefix);
                resultData.addImageUrl(imageUrl);
            }
            if(resultData.getImageCount() == 0) throw new htmlParsingException("getReaderData", "imageCounting");

        }catch(htmlParsingException e){
            Toast.makeText(this, "리더데이터 생성중 문제발생", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
        //여기까지 거쳤으면 readerData의 정보가 전부 차게 된다.
        //정보는 title(디렉토리 생성용), Queue<String> imageList(다운로드 접근용) 이다.

        return resultData;
    }
}
