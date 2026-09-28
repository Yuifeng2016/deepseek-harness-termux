package com.mermergi.dsh;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * First-run / repair guide, shown instead of the boot-failure screen when the Termux half
 * is missing. Three steps:
 *
 *   1. install Termux from the APK bundled in our assets (browser mirrors as fallback);
 *   2. one paste-and-run command that does the whole Termux side (bootstrap.sh);
 *   3. a last-page hint about entering the DeepSeek API key.
 *
 * The wizard only ever appears on failure paths — a working installation never sees it.
 */
final class SetupWizard {

    /** The fork this build installs from. Override point when forking again. */
    static final String BOOTSTRAP_URL =
            "https://raw.githubusercontent.com/Yuifeng2016/deepseek-harness-termux/main/bootstrap.sh";
    static final String BOOTSTRAP_URL_MIRROR =
            "https://cdn.jsdelivr.net/gh/Yuifeng2016/deepseek-harness-termux@main/bootstrap.sh";

    static final String BOOTSTRAP_COMMAND =
            "pkg update -y && pkg install -y curl && bash <(curl -fsSL '" + BOOTSTRAP_URL + "')";
    static final String BOOTSTRAP_COMMAND_MIRROR =
            "pkg update -y && pkg install -y curl && bash <(curl -fsSL '" + BOOTSTRAP_URL_MIRROR + "')";

    /** Bridge back into MainActivity without exposing its internals. */
    interface Host {
        /** Leave the wizard and re-run the boot/detection flow. */
        void recheck();

        /** Bring the Termux app to the foreground. */
        void openTermuxApp();

        /** Extract the bundled APK and hand it to the system installer. */
        void installTermuxBundled(Runnable onExtractionFailed);

        /** Open a mirror download page in the browser. */
        void openDownloadPage();

        void copyToClipboard(String text);
    }

    private final Activity activity;
    private final Host host;
    private final ScrollView scroll;
    private final LinearLayout column;
    private boolean mirror;
    /** Which step is on screen: 0 none, 1..3. */
    private int step;

    SetupWizard(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.scroll = new ScrollView(activity);
        this.scroll.setBackgroundColor(Color.parseColor("#0B0B0F"));
        this.column = new LinearLayout(activity);
        this.column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        this.column.setPadding(pad, dp(36), pad, pad);
        this.scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    View view() {
        return scroll;
    }

    /** 0 when nothing is shown, otherwise the step number on screen. */
    int currentStep() {
        return step;
    }

    // ------------------------------------------------------------------ step 1

    /** Termux missing, or an old build without RUN_COMMAND. */
    void showStep1(String warning) {
        step = 1;
        clear();
        title("第 1 步 · 安装 Termux");
        body("DSH 的本体跑在 Termux 里。点下面的按钮，用内置的 Termux "
                + TermuxInstaller.TERMUX_VERSION + " 安装包安装（约 110MB，不需要联网）。\n\n"
                + "系统可能先要求允许「安装未知应用」——允许后回到这里再点一次。");
        if (warning != null) {
            body(warning);
        }
        primary("安装 Termux（内置安装包）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.installTermuxBundled(new Runnable() {
                    @Override
                    public void run() {
                        toast("内置安装包解出失败，请用浏览器下载");
                        host.openDownloadPage();
                    }
                });
            }
        });
        secondary("浏览器打开下载页（备用）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.openDownloadPage();
            }
        });
        secondary("我已安装 Termux，继续", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.recheck();
            }
        });
        footnote("如果装的是 Play 商店版 Termux（旧版，不支持被外部拉起命令），请先卸载再用本页安装。");
    }

    // ------------------------------------------------------------------ step 2

    /** Termux present but the bridge is not answering: bootstrap not run / allow-external-apps off. */
    void showStep2() {
        step = 2;
        clear();
        title("第 2 步 · 一条命令装好 DSH");
        body("1. 点「复制命令」\n"
                + "2. 点「打开 Termux」，长按粘贴并回车\n"
                + "3. 会自动装齐 Node.js、编译工具、dsh 和补丁（约 500MB，首次 10-30 分钟）\n"
                + "4. 期间保持 Termux 在前台并接上电源，装完回到这里点「我已执行，检查」");
        commandBox(mirror ? BOOTSTRAP_COMMAND_MIRROR : BOOTSTRAP_COMMAND);
        primary("复制命令", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.copyToClipboard(mirror ? BOOTSTRAP_COMMAND_MIRROR : BOOTSTRAP_COMMAND);
                toast("已复制，去 Termux 粘贴并回车");
            }
        });
        secondary("打开 Termux", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.openTermuxApp();
            }
        });
        secondary(mirror ? "换回原始线路" : "换备用线路（国内镜像）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mirror = !mirror;
                showStep2();
            }
        });
        secondary("我已执行，检查", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.recheck();
            }
        });
        footnote("这条命令会打开 allow-external-apps（让 DSH App 能拉起 Termux 命令），"
                + "并安装 android-fix 补丁。想收回外部命令权限：把 ~/.termux/termux.properties 里的"
                + " allow-external-apps 改回 false，再执行 termux-reload-settings。");
    }

    // ------------------------------------------------------------------ step 3

    /** Everything installed and reachable; last-mile hint before the web UI takes over. */
    void showStep3() {
        step = 3;
        clear();
        title("完成 · 最后一步");
        body("服务已就绪。首次进入请在 Settings → Models 里填一次 DeepSeek API key"
                + "（会写入 ~/.dsh/.credentials.yaml，之后不用再填）。");
        primary("进入 DSH", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.recheck();
            }
        });
    }

    // ------------------------------------------------------------------ widgets

    private void clear() {
        column.removeAllViews();
        scroll.scrollTo(0, 0);
    }

    private void title(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(tv, matchWrap(dp(0), dp(14)));
    }

    private void body(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#C9C9D2"));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setLineSpacing(dp(3), 1f);
        column.addView(tv, matchWrap(dp(0), dp(10)));
    }

    private void commandBox(final String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#9CDCFE"));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setMovementMethod(ScrollingMovementMethod.getInstance());
        tv.setBackgroundColor(Color.parseColor("#16161D"));
        int pad = dp(10);
        tv.setPadding(pad, pad, pad, pad);
        column.addView(tv, matchWrap(dp(0), dp(14)));
    }

    private void primary(String label, View.OnClickListener listener) {
        column.addView(button(label, listener, true), matchWrap(dp(14), dp(4)));
    }

    private void secondary(String label, View.OnClickListener listener) {
        column.addView(button(label, listener, false), matchWrap(dp(0), dp(4)));
    }

    private Button button(String label, View.OnClickListener listener, boolean emphasized) {
        Button b = new Button(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT);
        if (emphasized) {
            b.setBackgroundColor(Color.parseColor("#2D4EF5"));
            b.setTextColor(Color.WHITE);
        } else {
            b.setBackgroundColor(Color.parseColor("#1D1D26"));
            b.setTextColor(Color.parseColor("#C9C9D2"));
        }
        b.setOnClickListener(listener);
        return b;
    }

    private void footnote(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#6E6E7A"));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setLineSpacing(dp(2), 1f);
        column.addView(tv, matchWrap(dp(12), dp(0)));
    }

    private void toast(String text) {
        android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show();
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin, int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = topMargin;
        p.bottomMargin = bottomMargin;
        return p;
    }


    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
