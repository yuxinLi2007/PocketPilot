package com.pocketpilot.mocktarget;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 第二页用于给无障碍 GLOBAL_ACTION_BACK 提供一个可观测的窗口变化。 */
public final class DetailActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(245, 247, 250));

        TextView title = new TextView(this);
        title.setText("Mock 第二页");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(24, 33, 47));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("此时发送 COMMAND_BACK，系统应返回上一页。\n若无障碍已启用，PocketPilot 会记录 before/after 窗口变化。");
        note.setTextSize(15);
        note.setTextColor(Color.rgb(92, 105, 125));
        note.setPadding(0, dp(16), 0, dp(20));
        root.addView(note);

        Button finish = new Button(this);
        finish.setText("手动返回上一页");
        finish.setAllCaps(false);
        finish.setOnClickListener(v -> finish());
        root.addView(finish, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
