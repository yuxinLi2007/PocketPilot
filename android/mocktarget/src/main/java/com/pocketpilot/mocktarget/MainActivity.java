package com.pocketpilot.mocktarget;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 测试替代目标：不是微信，也不是真实地图。
 * 它只证明“命令、包名、参数、显式回执”这条测试链路可运行。
 */
public final class MainActivity extends Activity {
    private static final String APP_PACKAGE = "com.pocketpilot.app";
    private static final int INK = Color.rgb(24, 33, 47);
    private static final int MUTED = Color.rgb(92, 105, 125);
    private static final int BLUE = Color.rgb(31, 111, 235);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String commandId = getIntent().getStringExtra("pp_command_id");
        String commandType = getIntent().getStringExtra("pp_command_type");
        String address = getIntent().getStringExtra("pp_address");
        String data = getIntent().getDataString();
        if (address == null || address.isEmpty()) {
            address = addressFromData(getIntent().getData());
        }
        setContentView(build(commandId, commandType, address, data));
        if (commandId != null && !commandId.startsWith("MANUAL-")) {
            sendAck(commandId, "Mock MainActivity onCreate: command=" + commandType + ", address=" + address);
        }
    }

    private ViewGroup build(String commandId, String commandType, String address, String data) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(245, 247, 250));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(root);

        TextView banner = text("MOCK 替代目标\n这不是微信，也不是真实地图", 20, Color.rgb(112, 74, 0), Typeface.BOLD);
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(255, 243, 205));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.rgb(242, 201, 76));
        banner.setBackground(bg);
        root.addView(banner, fullWidth());

        TextView title = text("命令已到达替代目标", 26, INK, Typeface.BOLD);
        title.setPadding(0, dp(22), 0, dp(8));
        root.addView(title);

        TextView body = text("command_id\n" + value(commandId)
                + "\n\ncommand\n" + value(commandType)
                + "\n\n收到的地址\n" + value(address)
                + "\n\ndata URI\n" + value(data), 15, INK, Typeface.NORMAL);
        body.setTextIsSelectable(true);
        body.setLineSpacing(dp(3), 1f);
        body.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.WHITE);
        card.setCornerRadius(dp(14));
        card.setStroke(dp(1), Color.rgb(224, 230, 239));
        body.setBackground(card);
        LinearLayout.LayoutParams bodyParams = fullWidth();
        bodyParams.topMargin = dp(8);
        root.addView(body, bodyParams);

        Button detail = button("进入第二页（用于测试返回命令）");
        detail.setOnClickListener(v -> startActivity(new Intent(this, DetailActivity.class)));
        LinearLayout.LayoutParams detailParams = fullWidth();
        detailParams.topMargin = dp(20);
        root.addView(detail, detailParams);

        Button back = button("关闭本页（系统返回）");
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backParams = fullWidth();
        backParams.topMargin = dp(8);
        root.addView(back, backParams);

        TextView note = text("只有同签名的 Mock 应用会发送 ACK。真实微信/地图不会发送，故只显示“已执行·未确认”。",
                12, MUTED, Typeface.NORMAL);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);
        return scroll;
    }

    private void sendAck(String commandId, String detail) {
        Intent ack = new Intent("com.pocketpilot.app.ACTION_TARGET_ACK");
        ack.setPackage(APP_PACKAGE);
        ack.putExtra("pp_command_id", commandId);
        ack.putExtra("target_package", getPackageName());
        ack.putExtra("target_status", "CONFIRMED");
        ack.putExtra("target_detail", detail);
        sendBroadcast(ack);
    }

    private static String addressFromData(Uri uri) {
        if (uri == null) {
            return "(无)";
        }
        String query = uri.getQueryParameter("q");
        return query == null || query.isEmpty() ? uri.toString() : Uri.decode(query);
    }

    private static String value(String value) {
        return value == null || value.isEmpty() ? "(空)" : value;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
        button.setMinHeight(dp(48));
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
