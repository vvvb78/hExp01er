package app.hitomila.main;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.support.v7.widget.CardView;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.crashlytics.android.Crashlytics;
import com.gun0912.tedpermission.PermissionListener;
import com.gun0912.tedpermission.TedPermission;

import org.apache.commons.lang3.StringEscapeUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;

import app.hitomila.R;
import app.hitomila.common.hitomiObjects.IndexData;
import app.hitomila.downloadService.DownloadService;

/**
 * Created by admin on 2016-11-01.
 */

public class RecyclerViewAdapter extends RecyclerView.Adapter<RecyclerViewAdapter.ViewHolder> {
    Context mContext;

    //Inflate 받은 부분에서 위젯별 할당
    public class ViewHolder extends RecyclerView.ViewHolder{
        public ImageView thumbNailView;
        public TextView titleTextView;
        public TextView typeTextView;
        public TextView languageTextView;
        public CardView  cardView;

        public ViewHolder(View itemView) {
            super(itemView);

            thumbNailView = (ImageView)itemView.findViewById(R.id.viewHolderThumbnailImageView);
            titleTextView = (TextView)itemView.findViewById(R.id.viewholderTitleTextView);
            typeTextView = (TextView)itemView.findViewById(R.id.viewholderTypeTextView);
            languageTextView = (TextView)itemView.findViewById(R.id.viewholderLanguageTextView);
            cardView = (CardView)itemView.findViewById(R.id.cardview);
        }
    }

    IndexData.node[] dataSet;

    public RecyclerViewAdapter(Context mContext){
        this.mContext = mContext;
    }

    public void setData(IndexData dataSet){
        this.dataSet = dataSet.getDatas();
    }


    //ViewHolder의 Infalte 담당
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View item = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.activity_main_index_viewholderitem, parent, false);
        return new ViewHolder(item);
    }

    //Listener등 기능삽입
    @Override
    public void onBindViewHolder(ViewHolder holder, final int position) {
        try {
            holder.titleTextView.setText(StringEscapeUtils.unescapeHtml4(URLDecoder.decode(dataSet[position].title.replace("%", "%25"),"UTF-8")));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        holder.languageTextView.setText(dataSet[position].mangaLangugae);
        holder.typeTextView.setText(dataSet[position].type);
        //글라이드는 비동기로 섬네일을 부른뒤, 리사이징해서(는 안함) 전달한다.
        Glide.with(mContext).load(dataSet[position].thumbnailUrl)//.override(150, 200)
                .into(holder.thumbNailView);

        holder.cardView.setLongClickable(true);
        holder.cardView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                new TedPermission(mContext)
                        .setPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.INTERNET)
                        .setDeniedMessage("파일접근권한이 없습니다. 권한을 허용해 주세요.")
                        .setPermissionListener(new PermissionListener() {
                            @Override
                            public void onPermissionGranted() {
                                if(((ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo() == null){
                                    Toast.makeText(mContext, "네트워크를 다시 켜주세요 -_-", Toast.LENGTH_SHORT).show();
                                }
                                else if(dataSet[position].plainUrl != null){
                                    Toast.makeText(mContext, "다운로드를 시작합니다", Toast.LENGTH_SHORT).show();
                                    Intent intent = new Intent(mContext, DownloadService.class);
                                    intent.putExtra("galleryUrl", dataSet[position].plainUrl);
                                    mContext.startService(intent);
                                }
                                else Crashlytics.log(dataSet[position].title + ", " + dataSet[position].plainUrl + " unexpected download Data error");
                            }

                            @Override
                            public void onPermissionDenied(ArrayList<String> arrayList) {
                                Toast.makeText(mContext, "권한거부됨\n" + arrayList.toString(), Toast.LENGTH_SHORT).show();
                            }
                        })
                        .check();
                return true;
            }
        });
    }

    @Override
    public int getItemCount() {
        if(dataSet != null)
            return dataSet.length;
        else return 0;
    }
}
