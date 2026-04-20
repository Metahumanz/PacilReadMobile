package com.metahumanz.pacilread;

import android.content.Intent;
import android.os.Bundle;
import com.metahumanz.pacilread.theme.ThemedActivity;

/**
 * MainActivity 现在仅作为一个路由器使用。
 * 它将所有请求重定向到 BookshelfActivity。
 */
public class MainActivity extends ThemedActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 重定向到新的主 Activity
        Intent intent = new Intent(this, BookshelfActivity.class);
        
        // 透传可能的 Extra 信息
        if (getIntent() != null && getIntent().getExtras() != null) {
            intent.putExtras(getIntent().getExtras());
        }
        
        startActivity(intent);
        
        // 结束自身
        finish();
    }
}
