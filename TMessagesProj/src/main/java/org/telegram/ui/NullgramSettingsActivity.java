package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.CreatorBadge;
import org.telegram.messenger.GhostMode;
import org.telegram.messenger.NullgramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.WsProxy;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.MaterialYou;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SeekBarView;

import java.util.ArrayList;

public class NullgramSettingsActivity extends BaseFragment {

    private static final int VT_HEADER = 0, VT_CHECK = 1, VT_SHADOW = 2, VT_BUTTON = 3,
        VT_SWATCHES = 4, VT_SEEK = 6, VT_EXTRAS = 7, VT_DENSITY = 8, VT_HUB = 9, VT_BANNER = 10, VT_MOTION_SENS = 11,
        VT_SEARCH = 12, VT_STATUS = 13, VT_PREVIEW = 14, VT_EMPTY = 15;

    public static final int PAGE_HUB = 0, PAGE_APPEARANCE = 1, PAGE_PRIVACY = 2, PAGE_CHATS = 3, PAGE_CONNECTION = 4, PAGE_PROFILE = 5, PAGE_ABOUT = 6, PAGE_HIDDEN = 7, PAGE_PLUGINS = 8, PAGE_STORE = 9;

    private static class Item {
        final int viewType;
        final int id;
        final String text;
        String sub;
        int icon, c1, c2;
        int page;

        Item(int viewType, int id, String text) {
            this.viewType = viewType;
            this.id = id;
            this.text = text;
        }

        Item sub(String sub) {
            this.sub = sub;
            return this;
        }

        Item hub(int icon, IconBackgroundColors colors) {
            this.icon = icon;
            this.c1 = colors.top;
            this.c2 = colors.bottom;
            return this;
        }
    }

    private static final int C_ENABLED = 1, C_AMOLED = 2, C_GHOST = 4, C_KEEP_DELETED = 5,
        C_EDIT_HISTORY = 6, C_SAVE_DISAPPEARING = 7, C_WS_PROXY = 8, C_FAST_DOWNLOADS = 9, C_NO_ADS = 10, C_CLEAN_LINKS = 11, C_HIDE_STORIES = 12, C_NO_SWIPE_REPLY = 13, C_HIDE_PHONE = 14, C_CONFIRM_CALLS = 15, C_SCREEN_GUARD = 16, C_DIAGNOSTICS = 17, C_HIDE_NOTIF_TEXT = 19, C_ANIMATIONS = 30, C_FAST_MODE = 31, C_SNOW = 32, C_MOTION = 33, C_WINTER_ICONS = 34, C_SHOW_BADGE = 35, C_STRIP_EXIF = 36, C_MOTION_ALWAYS = 37, C_SHOW_IDS = 38, C_FREE_TRANSLATE = 50, C_ROUND_BACK = 51, C_SYSTEM_EMOJI = 52, C_VOICE_HQ = 53, C_VOICE_NS = 54, C_VOICE_AGC = 55, C_CAMERA_FRONT = 56, C_CAMERA_GRID = 57;
    private static final int B_WALLPAPER = 20, B_TOOLS_INFO = 21, B_HOME_WP = 22, B_HOME_WP_OFF = 23, B_UPDATE = 24, B_SET_PIN = 40, B_LOCK_NOW = 41, B_SET_DECOY = 42, B_ICON_IMPORT = 43, B_ICON_RESET = 44, B_PLUGIN_IMPORT = 45, B_CAMERA = 46, B_STORE = 47;
    private static final int REQ_ICON_PACK = 4431, REQ_PLUGIN = 4432;
    private final ArrayList<String> pluginFiles = new ArrayList<>();

    private final int page;

    public NullgramSettingsActivity() {
        this(PAGE_HUB);
    }

    public NullgramSettingsActivity(int page) {
        this.page = page;
    }

    private String pageTitle() {
        return pageTitleOf(page);
    }

    private static String pageTitleOf(int p) {
        switch (p) {
            case PAGE_APPEARANCE: return MaterialYou.s("Appearance", "Внешний вид");
            case PAGE_PRIVACY: return MaterialYou.s("Privacy and protection", "Приватность и защита");
            case PAGE_CHATS: return MaterialYou.s("Chats", "Чаты");
            case PAGE_CONNECTION: return MaterialYou.s("Connection and content", "Соединение и контент");
            case PAGE_PROFILE: return MaterialYou.s("Profile", "Профиль");
            case PAGE_ABOUT: return MaterialYou.s("Updates and diagnostics", "Обновления и диагностика");
            case PAGE_HIDDEN: return MaterialYou.s("Hidden accounts", "Скрытые аккаунты");
            case PAGE_PLUGINS: return MaterialYou.s("Plugins", "Плагины");
            case PAGE_STORE: return MaterialYou.s("Plugin store", "Магазин плагинов");
            default: return "Yologram";
        }
    }

    private final ArrayList<Item> items = new ArrayList<>();
    private String query = "";
    private final ArrayList<org.telegram.messenger.PluginStore.Entry> storeEntries = new ArrayList<>();
    private boolean storeLoading;
    private String storeError;
    private PreviewRow previewRow;
    private RecyclerListView listView;
    private ListAdapter adapter;

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode == REQ_PLUGIN && resultCode == android.app.Activity.RESULT_OK && data != null && data.getData() != null) {
            final android.net.Uri uri = data.getData();
            final Context ctx = getParentActivity();
            org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                final String file = org.telegram.messenger.PluginEngine.importPlugin(ctx, uri);
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    if (file == null) {
                        android.widget.Toast.makeText(ctx, MaterialYou.s("Only .py plugins up to 512 KB", "Подходят только .py плагины до 512 КБ"), android.widget.Toast.LENGTH_LONG).show();
                    } else {
                        android.widget.Toast.makeText(ctx, MaterialYou.s("Added: ", "Добавлен: ") + file + MaterialYou.s(". Switch it on below.", ". Включи его в списке."), android.widget.Toast.LENGTH_LONG).show();
                        buildItems();
                    }
                });
            });
            return;
        }
        if (requestCode == REQ_ICON_PACK && resultCode == android.app.Activity.RESULT_OK && data != null && data.getData() != null) {
            final android.net.Uri uri = data.getData();
            final Context ctx = getParentActivity();
            org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                final String name = org.telegram.messenger.IconPack.importPack(ctx, uri);
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    if (name == null) {
                        android.widget.Toast.makeText(ctx, MaterialYou.s("Not an icon pack", "Это не набор иконок"), android.widget.Toast.LENGTH_LONG).show();
                    } else {
                        android.widget.Toast.makeText(ctx, MaterialYou.s("Icon pack applied: ", "Набор иконок применён: ") + name, android.widget.Toast.LENGTH_LONG).show();
                        if (LaunchActivity.instance != null) {
                            LaunchActivity.instance.rebuildAllFragments(true);
                        }
                    }
                });
            });
            return;
        }
        if (requestCode == org.telegram.ui.Components.HomeWallpaper.REQUEST_CODE) {
            org.telegram.ui.Components.HomeWallpaper.onPicked(resultCode, data);
        }
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);

        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(pageTitle());
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        final FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.setVerticalScrollBarEnabled(false);
        // grouped cards: rows between two headers/footers sit on one rounded card (drawn by the decoration below)
        listView.setPadding(dp(12), 0, dp(12), dp(24));
        listView.setClipToPadding(false);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView.addItemDecoration(new CardDecoration());
        listView.setLayoutAnimation(null);
        listView.setAdapter(adapter = new ListAdapter());
        final DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setDurations(350);
        itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setSupportsChangeAnimations(false);
        listView.setItemAnimator(itemAnimator);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size()) {
                return;
            }
            final Item item = items.get(position);
            if (item.viewType == VT_BUTTON && item.page != 0) {
                presentFragment(new NullgramSettingsActivity(item.page));
                return;
            }
            if (item.viewType == VT_BUTTON && item.id == B_STORE) {
                presentFragment(new NullgramSettingsActivity(PAGE_STORE));
                return;
            }
            if (item.viewType == VT_BUTTON && item.id >= 5000 && item.id < 6000) {
                installFromStore(item.id - 5000);
                return;
            }
            if (item.viewType == VT_CHECK) {
                final boolean value = !isChecked(item.id);
                setChecked(item.id, value);
                ((TextCheckCell) view).setChecked(value);
                tick(view, value);
                if (previewRow != null) {
                    previewRow.invalidate();
                }
            } else if (item.viewType == VT_BUTTON && item.id == B_WALLPAPER) {
                presentFragment(new WallpapersListActivity(WallpapersListActivity.TYPE_ALL));
            } else if (item.viewType == VT_BUTTON && item.id == B_HOME_WP) {
                org.telegram.ui.Components.HomeWallpaper.pick(getParentActivity());
            } else if (item.viewType == VT_BUTTON && item.id == B_CAMERA) {
                presentFragment(new YologramCameraActivity());
            } else if (item.viewType == VT_BUTTON && item.id == B_PLUGIN_IMPORT) {
                final AlertDialog.Builder warn = new AlertDialog.Builder(getParentActivity());
                warn.setTitle(MaterialYou.s("Plugins run code", "Плагины выполняют код"));
                warn.setMessage(MaterialYou.s("A plugin is a Python program with access to everything Yologram can do, including your chats. Install only plugins from people you trust.", "Плагин — это программа на Python с доступом ко всему, что умеет Yologram, включая ваши чаты. Ставьте плагины только от тех, кому доверяете."));
                warn.setPositiveButton(MaterialYou.s("Choose file", "Выбрать файл"), (d, w) -> {
                    final android.content.Intent pick = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                    pick.setType("*/*");
                    pick.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                    startActivityForResult(pick, REQ_PLUGIN);
                });
                warn.setNegativeButton(org.telegram.messenger.LocaleController.getString(R.string.Cancel), null);
                showDialog(warn.create());
            } else if (item.viewType == VT_BUTTON && item.id >= 3000 && item.id < 4000) {
                final int idx = item.id - 3000;
                if (idx < pluginFiles.size()) {
                    org.telegram.messenger.PluginEngine.remove(pluginFiles.get(idx));
                    buildItems();
                }
            } else if (item.viewType == VT_HUB && item.id == PAGE_PLUGINS) {
                presentFragment(new NullgramSettingsActivity(PAGE_PLUGINS));
            } else if (item.viewType == VT_BUTTON && item.id == B_ICON_IMPORT) {
                final android.content.Intent pick = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                pick.setType("*/*");
                pick.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                startActivityForResult(pick, REQ_ICON_PACK);
            } else if (item.viewType == VT_BUTTON && item.id == B_ICON_RESET) {
                org.telegram.messenger.IconPack.reset();
                if (LaunchActivity.instance != null) {
                    LaunchActivity.instance.rebuildAllFragments(true);
                }
            } else if (item.viewType == VT_BUTTON && item.id == B_HOME_WP_OFF) {
                org.telegram.ui.Components.HomeWallpaper.remove();
            } else if (item.viewType == VT_BUTTON && item.id == B_SET_PIN) {
                pinDialog(MaterialYou.s("New PIN (4-8 digits)", "Новый PIN (4–8 цифр)"), pin -> {
                    org.telegram.messenger.NullgramHidden.setPin(pin);
                    buildItems();
                });
            } else if (item.viewType == VT_BUTTON && item.id == B_SET_DECOY) {
                pinDialog(MaterialYou.s("Decoy PIN (4-8 digits)", "Ложный PIN (4–8 цифр)"), pin -> {
                    if (!org.telegram.messenger.NullgramHidden.setDecoyPin(pin)) {
                        android.widget.Toast.makeText(getParentActivity(), MaterialYou.s("It must differ from the real PIN", "Он должен отличаться от настоящего PIN"), android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        buildItems();
                    }
                });
            } else if (item.viewType == VT_BUTTON && item.id == B_LOCK_NOW) {
                org.telegram.messenger.NullgramHidden.lock();
                android.widget.Toast.makeText(getParentActivity(), MaterialYou.s("Accounts hidden", "Аккаунты скрыты"), android.widget.Toast.LENGTH_SHORT).show();
                finishFragment();
            } else if (item.viewType == VT_HUB) {
                if (item.id == B_UPDATE) {
                    checkForUpdate();
                } else {
                    presentFragment(new NullgramSettingsActivity(item.id));
                }
            }
        });

        buildItems();
        if (page == PAGE_STORE) {
            loadStore();
        }
        if (NullgramConfig.animations()) {
            final android.view.animation.AnimationSet set = new android.view.animation.AnimationSet(true);
            final android.view.animation.TranslateAnimation slide = new android.view.animation.TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_SELF, 0f, android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                android.view.animation.Animation.ABSOLUTE, dp(36), android.view.animation.Animation.ABSOLUTE, 0f);
            slide.setDuration(420);
            slide.setInterpolator(new android.view.animation.OvershootInterpolator(1.4f));
            final android.view.animation.AlphaAnimation fade = new android.view.animation.AlphaAnimation(0f, 1f);
            fade.setDuration(260);
            set.addAnimation(slide);
            set.addAnimation(fade);
            final android.view.animation.LayoutAnimationController lac = new android.view.animation.LayoutAnimationController(set, 0.07f);
            listView.setLayoutAnimation(lac);
            listView.scheduleLayoutAnimation();
        }
        return fragmentView;
    }

    private void pinDialog(String title, java.util.function.Consumer<String> onOk) {
        if (getParentActivity() == null) {
            return;
        }
        final EditText edit = new EditText(getParentActivity());
        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});
        edit.setSingleLine(true);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setPadding(dp(4), dp(8), dp(4), dp(8));
        final AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(title);
        b.setView(edit);
        b.setPositiveButton("OK", (d, w) -> {
            final String pin = edit.getText().toString();
            if (pin.length() < 4) {
                android.widget.Toast.makeText(getParentActivity(), MaterialYou.s("At least 4 digits", "Минимум 4 цифры"), android.widget.Toast.LENGTH_SHORT).show();
            } else {
                onOk.accept(pin);
            }
        });
        b.setNegativeButton(MaterialYou.s("Cancel", "Отмена"), null);
        showDialog(b.create());
    }

    /** Opened by tapping the Yologram title five times on the main page. */
    private void promptUnlock() {
        if (!org.telegram.messenger.NullgramHidden.hasPin()) {
            return;
        }
        pinDialog(MaterialYou.s("PIN", "PIN"), pin -> {
            if (org.telegram.messenger.NullgramHidden.hasDecoyPin() && org.telegram.messenger.NullgramHidden.checkDecoyPin(pin)) {
                org.telegram.messenger.NullgramHidden.unlockDecoy();
                android.widget.Toast.makeText(getParentActivity(), MaterialYou.s("Hidden accounts are open", "Скрытые аккаунты открыты"), android.widget.Toast.LENGTH_SHORT).show();
                buildItems();
            } else if (org.telegram.messenger.NullgramHidden.checkPin(pin)) {
                org.telegram.messenger.NullgramHidden.unlock();
                android.widget.Toast.makeText(getParentActivity(), MaterialYou.s("Hidden accounts are open", "Скрытые аккаунты открыты"), android.widget.Toast.LENGTH_SHORT).show();
                buildItems();
            } else {
                android.widget.Toast.makeText(getParentActivity(), MaterialYou.s("Wrong PIN", "Неверный PIN"), android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void checkForUpdate() {
        final android.app.Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        android.widget.Toast.makeText(activity, MaterialYou.s("Checking for updates…", "Проверяем обновления…"), android.widget.Toast.LENGTH_SHORT).show();
        org.telegram.messenger.NullgramUpdater.check((info, upToDate, error) -> {
            if (getParentActivity() == null) {
                return;
            }
            if (error != null) {
                android.widget.Toast.makeText(getParentActivity(), MaterialYou.s("Update check failed: ", "Не удалось проверить: ") + error, android.widget.Toast.LENGTH_LONG).show();
            } else if (info == null) {
                android.widget.Toast.makeText(getParentActivity(), MaterialYou.s("You have the latest version", "У тебя последняя версия"), android.widget.Toast.LENGTH_SHORT).show();
            } else {
                final String size = info.size > 0 ? String.format(java.util.Locale.US, " (%.1f MB)", info.size / 1048576.0) : "";
                final AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                b.setTitle(MaterialYou.s("Update available: ", "Доступно обновление: ") + info.versionName);
                b.setMessage((info.notes.isEmpty() ? "" : info.notes + "\n\n") + MaterialYou.s("Download and install", "Скачать и установить") + size + "?");
                b.setPositiveButton(MaterialYou.s("Update", "Обновить"), (d, w) -> downloadUpdate(info));
                b.setNegativeButton(MaterialYou.s("Later", "Позже"), null);
                showDialog(b.create());
            }
        });
    }

    private void downloadUpdate(final org.telegram.messenger.NullgramUpdater.Info info) {
        final android.app.Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        final UpdateProgressView bar = new UpdateProgressView(activity);
        final TextView percent = new TextView(activity);
        percent.setTextSize(28);
        percent.setTypeface(AndroidUtilities.bold());
        percent.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        percent.setText("0%");
        final TextView sizeText = new TextView(activity);
        sizeText.setTextSize(14);
        sizeText.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        final double totalMb = info.size / 1048576.0;
        sizeText.setText(totalMb > 0 ? String.format(java.util.Locale.US, "0.0 / %.1f MB", totalMb) : "");
        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(4), dp(24), dp(8));
        box.addView(percent, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        box.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 12, 0, 10, 0, 8));
        box.addView(sizeText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        final AlertDialog progress = new AlertDialog.Builder(activity).setTitle(MaterialYou.s("Downloading update", "Загрузка обновления")).setView(box).create();
        progress.setCanCancel(false);
        progress.show();
        org.telegram.messenger.NullgramUpdater.download(info, new org.telegram.messenger.NullgramUpdater.DownloadCallback() {
            @Override
            public void onProgress(int p) {
                percent.setText(p + "%");
                bar.setProgress(p / 100f);
                if (totalMb > 0) {
                    sizeText.setText(String.format(java.util.Locale.US, "%.1f / %.1f MB", totalMb * p / 100.0, totalMb));
                }
            }

            @Override
            public void onDone(java.io.File apk, String error) {
                bar.setProgress(error == null ? 1f : bar.current());
                progress.dismiss();
                final android.app.Activity a = getParentActivity();
                if (a == null) {
                    return;
                }
                if (error != null) {
                    android.widget.Toast.makeText(a, MaterialYou.s("Download failed: ", "Ошибка загрузки: ") + error, android.widget.Toast.LENGTH_LONG).show();
                } else if (!org.telegram.messenger.NullgramUpdater.install(a, apk)) {
                    android.widget.Toast.makeText(a, MaterialYou.s("Allow installs from Yologram, then tap Update again", "Разреши установку из Yologram и нажми «Обновить» ещё раз"), android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** Material You progress slider: rounded track, rounded fill and a round handle that glides to the new value. */
    private static class UpdateProgressView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private float target, shown;

        UpdateProgressView(Context context) {
            super(context);
        }

        void setProgress(float p) {
            target = Math.max(0f, Math.min(1f, p));
            invalidate();
        }

        float current() {
            return target;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            shown += (target - shown) * 0.22f;
            if (Math.abs(target - shown) > 0.001f) {
                postInvalidateOnAnimation();
            } else {
                shown = target;
            }
            track.setColor(MaterialYou.role(MaterialYou.R_SECONDARY_CONTAINER));
            fill.setColor(MaterialYou.role(MaterialYou.R_PRIMARY));
            final float h = getHeight(), w = getWidth();
            final float barH = dp(8), top = (h - barH) / 2f, r = barH / 2f;
            rect.set(0, top, w, top + barH);
            canvas.drawRoundRect(rect, r, r, track);
            final float x = Math.max(r, w * shown);
            rect.set(0, top, x, top + barH);
            canvas.drawRoundRect(rect, r, r, fill);
            // handle: a pill that sits on the end of the fill with a small gap, as in Material sliders
            final float hx = Math.max(dp(3), Math.min(w - dp(3), x + dp(4)));
            rect.set(hx - dp(2), h / 2f - dp(8), hx + dp(2), h / 2f + dp(8));
            canvas.drawRoundRect(rect, dp(2), dp(2), fill);
        }
    }

    @androidx.annotation.Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    private static String ru(String en, String ru) {
        return MaterialYou.s(en, ru);
    }

    private void check(int id, String en, String ruText, String subEn, String subRu) {
        items.add(new Item(VT_CHECK, id, ru(en, ruText)).sub(subEn == null ? null : ru(subEn, subRu)));
    }

    private void footer(String en, String ruText) {
        items.add(new Item(VT_SHADOW, 0, ru(en, ruText)));
    }

    private void buildItems() {
        fillItems(page);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void fillItems(int p) {
        items.clear();
        switch (p) {
            case PAGE_APPEARANCE:
                items.add(new Item(VT_HEADER, 0, ru("Preview", "Предпросмотр")));
                items.add(new Item(VT_PREVIEW, 0, null));
                footer("", "");
                items.add(new Item(VT_HEADER, 0, ru("Color", "Цвет")));
                items.add(new Item(VT_SWATCHES, 0, null));
                items.add(new Item(VT_HEADER, 0, ru("Chat list density", "Плотность списка чатов")));
                items.add(new Item(VT_DENSITY, 0, null));
                items.add(new Item(VT_HEADER, 0, ru("Roundness", "Скругление")));
                items.add(new Item(VT_SEEK, 0, null));
                check(C_ENABLED, "Material You design", "Дизайн Material You", "Rounded shapes and colours from your theme", "Скруглённые формы и цвета из твоей темы");
                check(C_AMOLED, "AMOLED black", "AMOLED-чёрный", "Pure black background in the dark theme", "Чисто чёрный фон в тёмной теме");
                footer("Roundness applies to new dialogs, buttons and chat cards after reopening.", "Скругление применяется к новым окнам, кнопкам и карточкам чатов после повторного открытия.");
                items.add(new Item(VT_HEADER, 0, ru("Effects", "Эффекты")));
                check(C_SNOW, "Falling snow", "Падающий снег", "Snowfall over every screen", "Снегопад поверх всех экранов");
                check(C_WINTER_ICONS, "Snowman and tree icons", "Значки снеговика и ёлки", "In the chat list title and Yologram settings; shown while snow is on. Reopen the screen to see the change", "В заголовке списка чатов и настройках Yologram, пока включён снег. Чтобы увидеть изменение, открой экран заново");
                footer("", "");
                items.add(new Item(VT_HEADER, 0, ru("Icon pack", "Набор иконок")));
                items.add(new Item(VT_BUTTON, B_ICON_IMPORT, org.telegram.messenger.IconPack.isActive() ? ru("Replace pack (current: ", "Заменить набор (сейчас: ") + org.telegram.messenger.IconPack.activeName() + ")" : ru("Load an icon pack (.icons)", "Загрузить набор иконок (.icons)")));
                if (org.telegram.messenger.IconPack.isActive()) {
                    items.add(new Item(VT_BUTTON, B_ICON_RESET, ru("Back to built-in icons", "Вернуть встроенные иконки")));
                }
                footer("Packs made for exteraGram (.icons) work: icons are swapped by name while the app runs.", "Подходят наборы для exteraGram (.icons): иконки подменяются по имени прямо во время работы.");
                items.add(new Item(VT_HEADER, 0, ru("Wallpapers", "Обои")));
                items.add(new Item(VT_BUTTON, B_WALLPAPER, ru("Chat wallpaper", "Обои чата")));
                items.add(new Item(VT_BUTTON, B_HOME_WP, ru("Main screen wallpaper", "Обои главного экрана")));
                items.add(new Item(VT_BUTTON, B_HOME_WP_OFF, ru("Remove main screen wallpaper", "Убрать обои главного экрана")));
                footer("", "");
                break;
            case PAGE_PRIVACY:
                items.add(new Item(VT_HEADER, 0, ru("Traces", "Следы")));
                check(C_GHOST, "Ghost mode", "Режим призрака", "Hides online status, read receipts, typing and story views", "Скрывает «в сети», прочтение, набор текста и просмотры историй");
                check(C_KEEP_DELETED, "Keep deleted messages", "Удалённые сообщения", "Messages deleted by others stay in the chat", "Удалённые собеседником сообщения остаются в чате");
                check(C_EDIT_HISTORY, "Keep edit history", "История правок", "Long-press an edited message to see earlier versions", "Долгое нажатие на изменённое сообщение покажет прежние версии");
                check(C_SAVE_DISAPPEARING, "Save disappearing media", "Исчезающие фото и видео", "Self-destructing photos and videos are saved", "Фото и видео с таймером сохраняются");
                footer("Silent read per chat: open a chat → ⋮.", "Тихое прочтение для отдельного чата: открой чат → ⋮.");
                items.add(new Item(VT_HEADER, 0, ru("Screen", "Экран")));
                check(C_SCREEN_GUARD, "Block screenshots", "Запретить скриншоты", "Also hides the app in the recent apps list", "Скрывает приложение и в списке недавних");
                check(C_HIDE_NOTIF_TEXT, "Hide text in notifications", "Скрывать текст в уведомлениях", "Notifications show no message text", "В уведомлениях не показывается текст сообщений");
                footer("", "");
                items.add(new Item(VT_HEADER, 0, ru("Personal data", "Личные данные")));
                check(C_SHOW_IDS, "Show IDs", "Показывать ID", "Telegram IDs of users, groups and channels in their profile; tap to copy", "ID пользователей, групп и каналов в профиле; нажми, чтобы скопировать");
                check(C_STRIP_EXIF, "Strip photo metadata in files", "Стирать метаданные фото в файлах", "Removes location, camera model and time from JPEG files you send as a file", "Убирает геопозицию, модель камеры и время из JPEG, которые ты отправляешь файлом");
                check(C_HIDE_PHONE, "Hide my phone number", "Скрыть мой номер телефона", "Masked in your profile and settings", "Скрыт в профиле и настройках");
                check(C_CONFIRM_CALLS, "Confirm before calling", "Подтверждать звонок", "Ask before every call starts", "Спрашивать перед каждым звонком");
                footer("", "");
                break;
            case PAGE_CHATS:
                items.add(new Item(VT_HEADER, 0, ru("Performance", "Производительность")));
                check(C_FAST_MODE, "Fast mode", "Быстрый режим", "Lighter glass effects, keeps the profile tab ready; smoother on weaker phones", "Облегчённые эффекты стекла, вкладка профиля всегда готова; плавнее на слабых телефонах");
                footer("", "");
                items.add(new Item(VT_HEADER, 0, ru("Comfort", "Комфорт")));
                check(C_MOTION, "Motion sickness protection", "Защита от укачивания", "Dots at the screen edges shift with the phone's motion, like iPhone vehicle cues", "Точки по краям экрана смещаются вместе с движением телефона, как в iPhone");
                check(C_MOTION_ALWAYS, "Always visible", "Показывать всегда", "Off: the dots appear only while you are moving (car, bus, train) and fade away when it calms down", "Выключено: точки появляются только в движении (машина, автобус, поезд) и гаснут, когда всё стихает");
                items.add(new Item(VT_HEADER, 0, ru("Sensitivity", "Чувствительность")));
                items.add(new Item(VT_MOTION_SENS, 0, null));
                footer("Uses the motion sensor only while the app is open.", "Использует датчик движения только пока приложение открыто.");
                items.add(new Item(VT_HEADER, 0, ru("Behaviour", "Поведение")));
                check(C_ANIMATIONS, "Springy touch animations", "Пружинящие анимации касаний", "Chats and settings rows bounce when pressed", "Строки чатов и настроек пружинят при нажатии");
                check(C_NO_SWIPE_REPLY, "Disable swipe to reply", "Отключить свайп-ответ", "No accidental replies while scrolling", "Нет случайных ответов при прокрутке");
                check(C_HIDE_STORIES, "Hide stories bar", "Скрыть истории", "Removes the stories row from the chat list", "Убирает ряд историй над списком чатов");
                check(C_FREE_TRANSLATE, "Translate without Premium", "Перевод без Premium", "Translating a chat sends its text to Google Translate", "Перевод чата отправляет его текст в Google Переводчик");
                check(C_SYSTEM_EMOJI, "System emoji", "Системные эмодзи", "Use the phone's own emoji instead of Telegram's", "Эмодзи телефона вместо эмодзи Telegram");
                check(C_VOICE_HQ, "High quality voice messages", "Голосовые высокого качества", "Records at 64 kbit/s instead of 30; files are about twice as big", "Запись 64 кбит/с вместо 30; файлы примерно вдвое больше");
                check(C_VOICE_NS, "Microphone noise suppression", "Шумоподавление микрофона", "Uses the phone's own noise suppressor while recording voice messages", "Встроенное шумоподавление телефона при записи голосовых");
                check(C_VOICE_AGC, "Microphone auto gain", "Автоусиление микрофона", "Evens out quiet and loud speech", "Выравнивает тихую и громкую речь");
                items.add(new Item(VT_BUTTON, B_CAMERA, ru("Open Yologram camera", "Открыть камеру Yologram")));
                check(C_CAMERA_FRONT, "Chat camera: front lens", "Камера в чате: фронтальная", "The camera in the attach menu opens on the front lens", "Камера в меню вложений открывается на фронтальном объективе");
                check(C_CAMERA_GRID, "Chat camera: grid", "Камера в чате: сетка", "Rule-of-thirds lines over the open camera", "Линии третей поверх открытой камеры");
                check(C_ROUND_BACK, "Round videos: back camera", "Кружки: задняя камера", "Start recording round videos with the back camera", "Запись кружков начинается с задней камеры");
                check(C_CLEAN_LINKS, "Strip tracking from links", "Чистить ссылки от трекеров", "Removes utm and similar parameters", "Убирает utm и похожие параметры");
                footer("In a chat: ⋮ → Snippets, Export, Silent read. Long-press a message → Details and Edit history.", "В чате: ⋮ → Шаблоны, Экспорт, Тихое прочтение. Долгое нажатие на сообщение → Подробности и История правок.");
                break;
            case PAGE_CONNECTION:
                items.add(new Item(VT_HEADER, 0, ru("Network", "Сеть")));
                check(C_WS_PROXY, "WS proxy", "WS-прокси", "Telegram over WebSocket; helps when the plain connection is blocked", "Telegram через WebSocket; помогает, когда обычное соединение блокируют");
                check(C_FAST_DOWNLOADS, "Faster downloads", "Быстрые загрузки", "More parallel requests for large files", "Больше параллельных запросов для больших файлов");
                footer("", "");
                items.add(new Item(VT_HEADER, 0, ru("Content", "Контент")));
                check(C_NO_ADS, "Remove ads", "Убрать рекламу", "Hides sponsored messages", "Скрывает рекламные сообщения");
                footer("", "");
                break;
            case PAGE_PROFILE: {
                final TLRPC.User me = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
                items.add(new Item(VT_HEADER, 0, ru("Profile", "Профиль")));
                check(C_SHOW_BADGE, "Yologram badge", "Значок Yologram", "Other Yologram users see an N badge next to your name", "Другие пользователи Yologram видят значок N рядом с твоим именем");
                if (CreatorBadge.isCreator(me)) {
                    items.add(new Item(VT_EXTRAS, 0, null));
                    footer("Extra usernames are shown on your profile in this app only; Telegram and other people don't see them.", "Доп. юзернеймы показываются на твоём профиле только в этом приложении; Telegram и другие люди их не видят.");
                }
                footer("Banner and thought: open the Profile tab and tap the banner card or the bubble near your avatar. Other Yologram users can see them.", "Баннер и мысль: открой вкладку «Профиль» и нажми карточку баннера или облачко у аватара. Их видят другие пользователи Yologram.");
                break;
            }
            case PAGE_HIDDEN: {
                items.add(new Item(VT_HEADER, 0, ru("Accounts", "Аккаунты")));
                int shownAccounts = 0;
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated() && !(org.telegram.messenger.NullgramHidden.isDecoy() && org.telegram.messenger.NullgramHidden.isHidden(a))) {
                        final String name = org.telegram.messenger.UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser());
                        items.add(new Item(VT_CHECK, 1000 + a, name).sub(org.telegram.messenger.NullgramHidden.isHidden(a) ? ru("Hidden until the PIN is entered", "Скрыт, пока не введён PIN") : ru("Visible to everyone", "Виден всем")));
                        shownAccounts++;
                    }
                }
                if (shownAccounts < 2) {
                    final Item empty = new Item(VT_EMPTY, 0, ru("Only one account", "Пока один аккаунт"));
                    empty.sub = ru("Add a second account in Telegram settings, then you can hide one of them", "Добавь второй аккаунт в настройках Telegram, и один из них можно будет скрыть");
                    empty.icon = R.drawable.msg_secret;
                    items.add(empty);
                }
                footer("A hidden account leaves the account list and stays silent: no notifications. At least one account has to stay visible.", "Скрытый аккаунт пропадает из списка аккаунтов и молчит: без уведомлений. Хотя бы один аккаунт должен остаться видимым.");
                items.add(new Item(VT_HEADER, 0, ru("Access", "Доступ")));
                items.add(new Item(VT_BUTTON, B_SET_PIN, org.telegram.messenger.NullgramHidden.hasPin() ? ru("Change PIN", "Сменить PIN") : ru("Set PIN", "Задать PIN")));
                if (org.telegram.messenger.NullgramHidden.hasPin()) {
                    items.add(new Item(VT_BUTTON, B_SET_DECOY, org.telegram.messenger.NullgramHidden.hasDecoyPin() ? ru("Change decoy PIN", "Сменить ложный PIN") : ru("Set decoy PIN", "Задать ложный PIN")));
                    items.add(new Item(VT_BUTTON, B_LOCK_NOW, ru("Hide now", "Скрыть сейчас")));
                }
                footer("The decoy PIN opens this same screen but shows no hidden account. Give it up if someone makes you unlock.", "Ложный PIN открывает этот же экран, но не показывает ни одного скрытого аккаунта. Назови его, если тебя заставляют открыть.");
                footer("To open hidden accounts later: tap the Yologram name on the main Yologram settings page 5 times and enter the PIN. They hide again 20 seconds after you leave the app.", "Чтобы открыть скрытые аккаунты: нажми 5 раз на название Yologram на главной странице настроек Yologram и введи PIN. Через 20 секунд после выхода из приложения они снова скрываются.");
                break;
            }
            case PAGE_PLUGINS: {
                pluginFiles.clear();
                pluginFiles.addAll(org.telegram.messenger.PluginEngine.files());
                items.add(new Item(VT_HEADER, 0, ru("Installed plugins", "Установленные плагины")));
                if (pluginFiles.isEmpty()) {
                    final Item empty = new Item(VT_EMPTY, 0, ru("No plugins yet", "Плагинов пока нет"));
                    empty.sub = ru("Add a .py file below and switch it on", "Добавь файл .py ниже и включи его");
                    empty.icon = R.drawable.msg_bot;
                    items.add(empty);
                }
                for (int i = 0; i < pluginFiles.size(); i++) {
                    final org.telegram.messenger.PluginEngine.Info info = org.telegram.messenger.PluginEngine.info(pluginFiles.get(i));
                    String sub = (info.version.isEmpty() ? "" : "v" + info.version + " · ") + (info.description.isEmpty() ? pluginFiles.get(i) : info.description);
                    if (info.error != null) {
                        sub = ru("Error: ", "Ошибка: ") + info.error;
                    }
                    items.add(new Item(VT_CHECK, 2000 + i, info.name).sub(sub));
                }
                if (!pluginFiles.isEmpty()) {
                    footer("Plugins run in the background Python engine. A broken plugin never blocks sending.", "Плагины работают во встроенном движке Python. Сломанный плагин не мешает отправке сообщений.");
                    items.add(new Item(VT_HEADER, 0, ru("Remove", "Удалить")));
                    for (int i = 0; i < pluginFiles.size(); i++) {
                        items.add(new Item(VT_BUTTON, 3000 + i, ru("Remove ", "Удалить ") + org.telegram.messenger.PluginEngine.info(pluginFiles.get(i)).name));
                    }
                    footer("", "");
                }
                items.add(new Item(VT_HEADER, 0, ru("Add", "Добавить")));
                items.add(new Item(VT_BUTTON, B_STORE, ru("Plugin store", "Магазин плагинов")));
                items.add(new Item(VT_BUTTON, B_PLUGIN_IMPORT, ru("Add a plugin (.py)", "Добавить плагин (.py)")));
                footer("Hooks: on_load, on_message, on_send. API: toast, send_message, copy, my_id, get, set, log.", "Хуки: on_load, on_message, on_send. API: toast, send_message, copy, my_id, get, set, log.");
                break;
            }
            case PAGE_STORE: {
                items.add(new Item(VT_HEADER, 0, ru("Catalog", "Каталог")));
                if (storeLoading) {
                    final Item e = new Item(VT_EMPTY, 0, ru("Loading…", "Загрузка…"));
                    e.icon = R.drawable.msg_download;
                    items.add(e);
                } else if (storeError != null) {
                    final Item e = new Item(VT_EMPTY, 0, ru("Could not load the catalog", "Не удалось загрузить каталог"));
                    e.sub = storeError;
                    e.icon = R.drawable.msg_close;
                    items.add(e);
                } else if (storeEntries.isEmpty()) {
                    final Item e = new Item(VT_EMPTY, 0, ru("The catalog is empty", "Каталог пока пуст"));
                    e.icon = R.drawable.msg_bot;
                    items.add(e);
                } else {
                    final java.util.List<String> have = org.telegram.messenger.PluginEngine.files();
                    for (int i = 0; i < storeEntries.size(); i++) {
                        final org.telegram.messenger.PluginStore.Entry e = storeEntries.get(i);
                        final String meta = (e.version.isEmpty() ? "" : "v" + e.version + " · ") + (e.author.isEmpty() ? "" : e.author + " · ");
                        final String sub = (have.contains(e.file) ? ru("Installed · ", "Установлен · ") : "") + meta + e.description;
                        items.add(new Item(VT_BUTTON, 5000 + i, e.name).sub(sub));
                    }
                }
                footer("Catalog plugins are checked against a checksum, but they still run code. Tap one to install it, then switch it on in Plugins.", "Плагины каталога проверяются по контрольной сумме, но всё равно выполняют код. Нажми на плагин, чтобы установить его, и включи в разделе «Плагины».");
                break;
            }
            case PAGE_ABOUT:
                items.add(new Item(VT_HEADER, 0, ru("Updates", "Обновления")));
                items.add(new Item(VT_HUB, B_UPDATE, ru("Check for updates", "Проверить обновления")).sub(ru("Current version ", "Текущая версия ") + org.telegram.messenger.NullgramUpdater.installedName()).hub(R.drawable.msg_download, IconBackgroundColors.BLUE_DEEP));
                footer("Updates come from the Yologram server and are checked by signature.", "Обновления приходят с сервера Yologram и проверяются по подписи.");
                items.add(new Item(VT_HEADER, 0, ru("Diagnostics", "Диагностика")));
                check(C_DIAGNOSTICS, "Anonymous diagnostics", "Анонимная диагностика", "Crash reports, app version and device model", "Отчёты об ошибках, версия приложения и модель устройства");
                footer("No Telegram ids, phone numbers, usernames or chat content are sent. Only a random install id, feature flags and error traces.", "Идентификаторы Telegram, номера, имена пользователей и содержимое чатов не отправляются. Только случайный id установки, включённые функции и стек ошибок.");
                break;
            default:
                items.add(new Item(VT_BANNER, 0, null));
                items.add(new Item(VT_SEARCH, 0, null));
                addHubBody();
                break;
        }
    }

    private static final int[] SEARCH_PAGES = {PAGE_APPEARANCE, PAGE_PRIVACY, PAGE_CHATS, PAGE_CONNECTION, PAGE_PROFILE, PAGE_ABOUT};
    private static final int[] SEARCH_SECTIONS = {PAGE_APPEARANCE, PAGE_PRIVACY, PAGE_CHATS, PAGE_CONNECTION, PAGE_PLUGINS, PAGE_PROFILE, PAGE_HIDDEN, PAGE_ABOUT};

    /** Everything under the banner and the search field on the main page: the status card and sections, or search results. */
    private void addHubBody() {
        if (!query.isEmpty()) {
            addSearchResults();
            return;
        }
        items.add(new Item(VT_STATUS, 0, null));
        footer("", "");
        items.add(new Item(VT_HUB, PAGE_APPEARANCE, ru("Appearance", "Внешний вид")).sub(ru("Colour, density, wallpapers", "Цвет, плотность, обои")).hub(R.drawable.msg_colors, IconBackgroundColors.PURPLE));
        items.add(new Item(VT_HUB, PAGE_PRIVACY, ru("Privacy and protection", "Приватность и защита")).sub(ru("Ghost mode, hidden notifications", "Призрак, скрытые уведомления")).hub(R.drawable.settings_privacy, IconBackgroundColors.GREEN));
        items.add(new Item(VT_HUB, PAGE_CHATS, ru("Chats", "Чаты")).sub(ru("Animations, swipes, links", "Анимации, свайпы, ссылки")).hub(R.drawable.settings_chat, IconBackgroundColors.ORANGE));
        items.add(new Item(VT_HUB, PAGE_CONNECTION, ru("Connection and content", "Соединение и контент")).sub(ru("Proxy, downloads, ads", "Прокси, загрузки, реклама")).hub(R.drawable.settings_data, IconBackgroundColors.BLUE));
        items.add(new Item(VT_HUB, PAGE_PLUGINS, ru("Plugins", "Плагины")).sub(ru("Python extensions", "Расширения на Python")).hub(R.drawable.msg_bot, IconBackgroundColors.PURPLE));
        items.add(new Item(VT_HUB, PAGE_PROFILE, ru("Profile", "Профиль")).sub(ru("Banner, thoughts", "Баннер, мысли")).hub(R.drawable.settings_account, IconBackgroundColors.CYAN));
        footer("", "");
        if (!org.telegram.messenger.NullgramHidden.anyHidden() || org.telegram.messenger.NullgramHidden.isUnlocked() || org.telegram.messenger.NullgramHidden.isDecoy()) {
            items.add(new Item(VT_HUB, PAGE_HIDDEN, ru("Hidden accounts", "Скрытые аккаунты")).sub(ru("Lend your phone safely", "Режим «чужой телефон»")).hub(R.drawable.msg_secret, IconBackgroundColors.GRAY));
        }
        items.add(new Item(VT_HUB, PAGE_ABOUT, ru("Updates and diagnostics", "Обновления и диагностика")).sub(ru("Version ", "Версия ") + org.telegram.messenger.NullgramUpdater.installedName()).hub(R.drawable.settings_power, IconBackgroundColors.RED));
        footer("", "");
    }

    private boolean matches(String text) {
        return text != null && text.toLowerCase(java.util.Locale.getDefault()).contains(query);
    }

    private void addSearchResults() {
        final ArrayList<Item> head = new ArrayList<>(items);
        final ArrayList<Item> found = new ArrayList<>();
        for (int sec : SEARCH_SECTIONS) {
            if (sec == PAGE_HIDDEN && org.telegram.messenger.NullgramHidden.anyHidden() && !org.telegram.messenger.NullgramHidden.isUnlocked() && !org.telegram.messenger.NullgramHidden.isDecoy()) {
                continue;
            }
            if (matches(pageTitleOf(sec))) {
                final Item r = new Item(VT_BUTTON, 0, pageTitleOf(sec));
                r.page = sec;
                found.add(r);
            }
        }
        for (int p : SEARCH_PAGES) {
            fillItems(p);
            for (Item it : new ArrayList<>(items)) {
                if ((it.viewType != VT_CHECK && it.viewType != VT_BUTTON) || it.text == null || it.id >= 1000) {
                    continue;
                }
                if (!matches(it.text) && !matches(it.sub)) {
                    continue;
                }
                final Item r = new Item(it.viewType, it.id, it.text);
                r.sub = pageTitleOf(p) + (it.sub != null ? " · " + it.sub : "");
                r.page = p;
                found.add(r);
            }
        }
        items.clear();
        items.addAll(head);
        if (found.isEmpty()) {
            final Item empty = new Item(VT_EMPTY, 0, ru("Nothing found", "Ничего не найдено"));
            empty.sub = ru("Try another word, for example ghost, proxy or theme", "Попробуй другое слово, например призрак, прокси или тема");
            empty.icon = R.drawable.msg_search;
            items.add(empty);
        } else {
            items.addAll(found);
            footer("", "");
        }
    }

    private void onQueryChanged(String text) {
        final String next = text.trim().toLowerCase(java.util.Locale.getDefault());
        if (next.equals(query) || adapter == null || page != PAGE_HUB) {
            return;
        }
        query = next;
        final int keep = 2;
        final int old = items.size();
        while (items.size() > keep) {
            items.remove(items.size() - 1);
        }
        adapter.notifyItemRangeRemoved(keep, old - keep);
        addHubBody();
        adapter.notifyItemRangeInserted(keep, items.size() - keep);
    }

    private void loadStore() {
        storeLoading = true;
        storeError = null;
        buildItems();
        org.telegram.messenger.PluginStore.load((entries, error) -> {
            storeLoading = false;
            storeEntries.clear();
            if (entries != null) {
                storeEntries.addAll(entries);
            }
            storeError = error;
            if (adapter != null && getParentActivity() != null) {
                buildItems();
            }
        });
    }

    private void installFromStore(int index) {
        if (index < 0 || index >= storeEntries.size() || getParentActivity() == null) {
            return;
        }
        final org.telegram.messenger.PluginStore.Entry entry = storeEntries.get(index);
        final AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(entry.name);
        b.setMessage(entry.description + "\n\n" + MaterialYou.s("A plugin is a Python program with access to everything Yologram can do, including your chats. It stays switched off until you turn it on in Plugins.", "Плагин — это программа на Python с доступом ко всему, что умеет Yologram, включая ваши чаты. Он остаётся выключенным, пока вы не включите его в разделе «Плагины»."));
        b.setPositiveButton(MaterialYou.s("Install", "Установить"), (d, w) -> org.telegram.messenger.PluginStore.install(entry, error -> {
            final Context ctx = getParentActivity();
            if (ctx == null) {
                return;
            }
            if (error != null) {
                android.widget.Toast.makeText(ctx, MaterialYou.s("Could not install: ", "Не удалось установить: ") + error, android.widget.Toast.LENGTH_LONG).show();
            } else {
                android.widget.Toast.makeText(ctx, MaterialYou.s("Installed. Switch it on in Plugins.", "Установлен. Включи его в разделе «Плагины»."), android.widget.Toast.LENGTH_LONG).show();
                buildItems();
            }
        }));
        b.setNegativeButton(MaterialYou.s("Cancel", "Отмена"), null);
        showDialog(b.create());
    }

    private static void tick(View v, boolean on) {
        int kind = android.view.HapticFeedbackConstants.CLOCK_TICK;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            kind = on ? android.view.HapticFeedbackConstants.CONFIRM : android.view.HapticFeedbackConstants.REJECT;
        }
        v.performHapticFeedback(kind);
    }

    private static boolean isChecked(int id) {
        if (id >= 2000) {
            final java.util.List<String> files = org.telegram.messenger.PluginEngine.files();
            return id - 2000 < files.size() && org.telegram.messenger.PluginEngine.isEnabled(files.get(id - 2000));
        }
        if (id >= 1000) {
            return org.telegram.messenger.NullgramHidden.isHidden(id - 1000);
        }
        switch (id) {
            case C_SHOW_BADGE: return NullgramConfig.showBadge();
            case C_STRIP_EXIF: return NullgramConfig.stripExif();
            case C_SHOW_IDS: return NullgramConfig.showIds();
            case C_VOICE_HQ: return NullgramConfig.voiceHq();
            case C_VOICE_NS: return NullgramConfig.voiceNoise();
            case C_VOICE_AGC: return NullgramConfig.voiceAgc();
            case C_CAMERA_FRONT: return NullgramConfig.cameraFront();
            case C_CAMERA_GRID: return NullgramConfig.cameraGrid();
            case C_FREE_TRANSLATE: return NullgramConfig.freeTranslate();
            case C_ROUND_BACK: return NullgramConfig.roundBackCamera();
            case C_SYSTEM_EMOJI: return org.telegram.messenger.SharedConfig.useSystemEmoji;
            case C_ENABLED: return MaterialYou.isEnabled();
            case C_AMOLED: return MaterialYou.isAmoled();
            case C_GHOST: return GhostMode.isOn();
            case C_KEEP_DELETED: return GhostMode.isKeepDeleted();
            case C_EDIT_HISTORY: return NullgramConfig.editHistory();
            case C_SAVE_DISAPPEARING: return NullgramConfig.saveDisappearing();
            case C_WS_PROXY: return WsProxy.isEnabled();
            case C_FAST_DOWNLOADS: return NullgramConfig.fastDownloads();
            case C_NO_ADS: return NullgramConfig.noAds();
            case C_HIDE_STORIES: return NullgramConfig.hideStories();
            case C_NO_SWIPE_REPLY: return NullgramConfig.noSwipeReply();
            case C_HIDE_PHONE: return NullgramConfig.hidePhone();
            case C_CONFIRM_CALLS: return NullgramConfig.confirmCalls();
            case C_SCREEN_GUARD: return NullgramConfig.screenGuard();
            case C_CLEAN_LINKS: return NullgramConfig.cleanLinks();
            case C_DIAGNOSTICS: return NullgramConfig.diagnostics();
            case C_ANIMATIONS: return NullgramConfig.animations();
            case C_FAST_MODE: return NullgramConfig.fastMode();
            case C_SNOW: return NullgramConfig.snow();
            case C_WINTER_ICONS: return NullgramConfig.winterIcons() || (!NullgramConfig.snow() && false);
            case C_MOTION: return NullgramConfig.motionCues();
            case C_MOTION_ALWAYS: return NullgramConfig.motionAlways();
            case C_HIDE_NOTIF_TEXT: return NullgramConfig.hideNotificationText();
            default: return false;
        }
    }

    private void setChecked(int id, boolean v) {
        if (id >= 2000) {
            final java.util.List<String> files = org.telegram.messenger.PluginEngine.files();
            if (id - 2000 < files.size()) {
                final Context ctx = getParentActivity();
                org.telegram.messenger.PluginEngine.setEnabled(files.get(id - 2000), v, error -> {
                    if (error != null && ctx != null) {
                        android.widget.Toast.makeText(ctx, MaterialYou.s("Plugin failed: ", "Плагин не запустился: ") + error, android.widget.Toast.LENGTH_LONG).show();
                    }
                    buildItems();
                });
            }
            return;
        }
        if (id >= 1000) {
            if (org.telegram.messenger.NullgramHidden.isDecoy()) {
                buildItems();
                return;
            }
            if (v && !org.telegram.messenger.NullgramHidden.hasPin()) {
                android.widget.Toast.makeText(getParentActivity(), MaterialYou.s("Set a PIN first", "Сначала задай PIN"), android.widget.Toast.LENGTH_SHORT).show();
            } else if (!org.telegram.messenger.NullgramHidden.setHidden(id - 1000, v)) {
                android.widget.Toast.makeText(getParentActivity(), MaterialYou.s("At least one account must stay visible", "Хотя бы один аккаунт должен остаться видимым"), android.widget.Toast.LENGTH_SHORT).show();
            }
            buildItems();
            return;
        }
        switch (id) {
            case C_STRIP_EXIF: NullgramConfig.setStripExif(v); break;
            case C_SHOW_IDS: NullgramConfig.setShowIds(v); break;
            case C_VOICE_HQ: NullgramConfig.setVoiceHq(v); break;
            case C_VOICE_NS: NullgramConfig.setVoiceNoise(v); break;
            case C_VOICE_AGC: NullgramConfig.setVoiceAgc(v); break;
            case C_CAMERA_FRONT: NullgramConfig.setCameraFront(v); break;
            case C_CAMERA_GRID: NullgramConfig.setCameraGrid(v); break;
            case C_FREE_TRANSLATE: NullgramConfig.setFreeTranslate(v); break;
            case C_ROUND_BACK: NullgramConfig.setRoundBackCamera(v); break;
            case C_SYSTEM_EMOJI:
                org.telegram.messenger.SharedConfig.useSystemEmoji = v;
                org.telegram.messenger.MessagesController.getGlobalMainSettings().edit().putBoolean("useSystemEmoji", v).apply();
                break;
            case C_SHOW_BADGE:
                NullgramConfig.setShowBadge(v);
                org.telegram.messenger.NullgramSync.refreshHello();
                break;
            case C_ENABLED: MaterialYou.setEnabled(v); break;
            case C_AMOLED: MaterialYou.setAmoled(v); break;
            case C_GHOST: GhostMode.setOn(v); break;
            case C_KEEP_DELETED: GhostMode.setKeepDeleted(v); break;
            case C_EDIT_HISTORY: NullgramConfig.setEditHistory(v); break;
            case C_SAVE_DISAPPEARING: NullgramConfig.setSaveDisappearing(v); break;
            case C_WS_PROXY: WsProxy.setEnabled(v); break;
            case C_FAST_DOWNLOADS: NullgramConfig.setFastDownloads(v); break;
            case C_NO_ADS: NullgramConfig.setNoAds(v); break;
            case C_HIDE_STORIES: NullgramConfig.setHideStories(v); break;
            case C_NO_SWIPE_REPLY: NullgramConfig.setNoSwipeReply(v); break;
            case C_HIDE_PHONE: NullgramConfig.setHidePhone(v); break;
            case C_CONFIRM_CALLS: NullgramConfig.setConfirmCalls(v); break;
            case C_SCREEN_GUARD:
                NullgramConfig.setScreenGuard(v);
                if (getParentActivity() != null) {
                    if (v) getParentActivity().getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    else getParentActivity().getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                }
                break;
            case C_CLEAN_LINKS: NullgramConfig.setCleanLinks(v); break;
            case C_DIAGNOSTICS: NullgramConfig.setDiagnostics(v); break;
            case C_ANIMATIONS: NullgramConfig.setAnimations(v); break;
            case C_FAST_MODE: NullgramConfig.setFastMode(v); break;
            case C_SNOW:
                NullgramConfig.setSnow(v);
                org.telegram.ui.Components.EffectsOverlayView.refresh(getParentActivity());
                break;
            case C_WINTER_ICONS: NullgramConfig.setWinterIcons(v); break;
            case C_MOTION_ALWAYS: NullgramConfig.setMotionAlways(v); break;
            case C_MOTION:
                NullgramConfig.setMotionCues(v);
                org.telegram.ui.Components.EffectsOverlayView.refresh(getParentActivity());
                break;
            case C_HIDE_NOTIF_TEXT: NullgramConfig.setHideNotificationText(v); break;
            default: break;
        }
    }

    private static boolean isRowType(int t) {
        return t == VT_CHECK || t == VT_BUTTON || t == VT_SWATCHES || t == VT_SEEK || t == VT_EXTRAS || t == VT_DENSITY || t == VT_MOTION_SENS || t == VT_HUB;
    }

    /** Draws one rounded card behind every run of rows, with hairlines between rows that do not draw their own. */
    private class CardDecoration extends RecyclerView.ItemDecoration {
        private final android.graphics.Paint card = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint line = new android.graphics.Paint();
        private final android.graphics.RectF rect = new android.graphics.RectF();

        @Override
        public void onDraw(android.graphics.Canvas c, RecyclerView parent, RecyclerView.State state) {
            card.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            line.setColor(Theme.getColor(Theme.key_divider));
            final float r = MaterialYou.isEnabled() ? MaterialYou.radius(24) : AndroidUtilities.dp(16);
            final float left = parent.getPaddingLeft(), right = parent.getWidth() - parent.getPaddingRight();
            final float far = AndroidUtilities.dp(60);
            float top = 0, bottom = 0;
            boolean open = false;
            int lastPos = -2, firstPos = -1;
            for (int i = 0; i < parent.getChildCount(); i++) {
                final View ch = parent.getChildAt(i);
                final int pos = parent.getChildAdapterPosition(ch);
                if (pos < 0 || pos >= items.size()) {
                    continue;
                }
                final boolean row = isRowType(items.get(pos).viewType);
                final float ty = ch.getTranslationY();
                if (row && open && pos == lastPos + 1) {
                    // hairline between two rows of different kinds (same-kind rows draw their own divider)
                    final int a = items.get(pos - 1).viewType, b = items.get(pos).viewType;
                    if (a != b && b != VT_HUB) {
                        c.drawRect(left + AndroidUtilities.dp(20), ch.getTop() + ty, right - AndroidUtilities.dp(20), ch.getTop() + ty + 1, line);
                    } else if (a == VT_HUB && b == VT_HUB) {
                        c.drawRect(left + AndroidUtilities.dp(78), ch.getTop() + ty, right - AndroidUtilities.dp(20), ch.getTop() + ty + 1, line);
                    }
                    bottom = ch.getBottom() + ty;
                    lastPos = pos;
                    continue;
                }
                if (open) {
                    flush(c, left, right, top, bottom, firstPos, lastPos, r, far);
                    open = false;
                }
                if (row) {
                    open = true;
                    firstPos = lastPos = pos;
                    top = ch.getTop() + ty;
                    bottom = ch.getBottom() + ty;
                }
            }
            if (open) {
                flush(c, left, right, top, bottom, firstPos, lastPos, r, far);
            }
        }

        private void flush(android.graphics.Canvas c, float left, float right, float top, float bottom, int first, int last, float r, float far) {
            // a card that continues above or below the screen is drawn past the edge so no corner shows there
            if (first > 0 && isRowType(items.get(first - 1).viewType)) {
                top -= far;
            }
            if (last + 1 < items.size() && isRowType(items.get(last + 1).viewType)) {
                bottom += far;
            }
            rect.set(left, top, right, bottom);
            c.drawRoundRect(rect, r, r, card);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            final View view;
            switch (viewType) {
                case VT_HEADER:
                    view = new HeaderCell(context);
                    break;
                case VT_CHECK:
                    view = new TextCheckCell(context);
                    break;
                case VT_BUTTON:
                    view = new TextSettingsCell(context);
                    break;
                case VT_SWATCHES:
                    view = new SwatchesRow(context);
                    break;
                case VT_SEEK:
                    view = new RoundnessRow(context);
                    break;
                case VT_EXTRAS:
                    view = new ExtrasRow(context);
                    break;
                case VT_DENSITY:
                    view = new DensityRow(context);
                    break;
                case VT_MOTION_SENS:
                    view = new MotionSensRow(context);
                    break;
                case VT_HUB:
                    view = new SettingsActivity.SettingCell(context, null);
                    break;
                case VT_BANNER:
                    view = new BannerRow(context, NullgramSettingsActivity.this::promptUnlock);
                    break;
                case VT_SEARCH:
                    view = new SearchRow(context, NullgramSettingsActivity.this::onQueryChanged);
                    break;
                case VT_STATUS:
                    view = new StatusRow(context);
                    break;
                case VT_PREVIEW:
                    view = previewRow = new PreviewRow(context);
                    break;
                case VT_EMPTY:
                    view = new EmptyRow(context);
                    break;
                default:
                    view = new TextInfoPrivacyCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= items.size()) {
                return;
            }
            final Item item = items.get(position);
            final boolean divider = position + 1 < items.size() && items.get(position + 1).viewType == item.viewType;
            switch (item.viewType) {
                case VT_HEADER:
                    ((HeaderCell) holder.itemView).setText(item.text);
                    break;
                case VT_CHECK:
                    if (item.sub != null) {
                        ((TextCheckCell) holder.itemView).setTextAndValueAndCheck(item.text, item.sub, isChecked(item.id), true, divider);
                    } else {
                        ((TextCheckCell) holder.itemView).setTextAndCheck(item.text, isChecked(item.id), divider);
                    }
                    break;
                case VT_HUB:
                    ((SettingsActivity.SettingCell) holder.itemView).set(item.c1, item.c2, item.icon, item.text, item.sub, null);
                    break;
                case VT_BUTTON:
                    if (item.sub != null) {
                        ((TextSettingsCell) holder.itemView).setTextAndValue(item.text, item.sub, divider);
                    } else {
                        ((TextSettingsCell) holder.itemView).setText(item.text, false);
                    }
                    break;
                case VT_SHADOW: {
                    final TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (TextUtils.isEmpty(item.text)) {
                        cell.setFixedSize(12);
                        cell.setText(null);
                    } else {
                        cell.setFixedSize(0);
                        cell.setText(item.text);
                    }
                    break;
                }
                case VT_SWATCHES:
                    ((SwatchesRow) holder.itemView).refresh();
                    break;
                case VT_DENSITY:
                    ((DensityRow) holder.itemView).refresh();
                    break;
                case VT_MOTION_SENS:
                    ((MotionSensRow) holder.itemView).refresh();
                    break;
                case VT_STATUS:
                    ((StatusRow) holder.itemView).refresh();
                    break;
                case VT_EMPTY:
                    ((EmptyRow) holder.itemView).set(item.icon, item.text, item.sub);
                    break;
                default:
                    break;
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).viewType;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int t = holder.getItemViewType();
            return t == VT_CHECK || t == VT_BUTTON || t == VT_HUB;
        }
    }

    /** Search field at the top of the main page; the rows below turn into matches from every section as you type. */
    private class SearchRow extends FrameLayout {
        SearchRow(Context context, java.util.function.Consumer<String> onText) {
            super(context);
            setPadding(dp(0), dp(4), dp(0), dp(8));
            final LinearLayout pill = new LinearLayout(context);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setBackground(Theme.createRoundRectDrawable((int) MaterialYou.radius(28), Theme.getColor(Theme.key_windowBackgroundWhite)));
            final android.widget.ImageView icon = new android.widget.ImageView(context);
            icon.setImageResource(R.drawable.msg_search);
            icon.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
            pill.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 16, 0, 0, 0));
            final EditText input = new EditText(context);
            input.setSingleLine(true);
            input.setBackground(null);
            input.setHint(MaterialYou.s("Search settings", "Поиск по настройкам"));
            input.setTextSize(16);
            input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            input.setPadding(dp(12), dp(14), dp(8), dp(14));
            pill.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            final android.widget.ImageView clear = new android.widget.ImageView(context);
            clear.setImageResource(R.drawable.msg_close);
            clear.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
            clear.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            clear.setVisibility(GONE);
            clear.setOnClickListener(v -> input.setText(""));
            pill.addView(clear, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    clear.setVisibility(s.length() > 0 ? VISIBLE : GONE);
                    onText.accept(s.toString());
                }
            });
            addView(pill, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 52));
        }
    }

    /** Three tiles at the top of the main page: ghost mode, kept deleted messages and the proxy, each switched with one tap. */
    private class StatusRow extends LinearLayout {
        private final int[] ids = {C_GHOST, C_KEEP_DELETED, C_WS_PROXY};
        private final int[] icons = {R.drawable.msg_secret, R.drawable.msg_delete, R.drawable.settings_data};
        private final String[] names = {MaterialYou.s("Ghost", "Призрак"), MaterialYou.s("Deleted", "Удалённые"), MaterialYou.s("Proxy", "Прокси")};
        private final LinearLayout[] tiles = new LinearLayout[3];
        private final android.widget.ImageView[] glyphs = new android.widget.ImageView[3];
        private final TextView[] titles = new TextView[3];
        private final TextView[] states = new TextView[3];

        StatusRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(0, dp(4), 0, dp(4));
            for (int i = 0; i < 3; i++) {
                final int index = i;
                final LinearLayout tile = new LinearLayout(context);
                tile.setOrientation(VERTICAL);
                tile.setPadding(dp(14), dp(14), dp(14), dp(14));
                final android.widget.ImageView glyph = new android.widget.ImageView(context);
                glyph.setImageResource(icons[i]);
                tile.addView(glyph, LayoutHelper.createLinear(26, 26));
                final TextView title = new TextView(context);
                title.setText(names[i]);
                title.setTextSize(15);
                title.setTypeface(AndroidUtilities.bold());
                title.setSingleLine(true);
                tile.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
                final TextView state = new TextView(context);
                state.setTextSize(13);
                tile.addView(state, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
                tile.setOnClickListener(v -> {
                    final boolean value = !isChecked(ids[index]);
                    setChecked(ids[index], value);
                    tick(v, value);
                    refresh();
                    v.animate().cancel();
                    v.setScaleX(0.9f);
                    v.setScaleY(0.9f);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
                });
                tiles[i] = tile;
                glyphs[i] = glyph;
                titles[i] = title;
                states[i] = state;
                addView(tile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, i == 0 ? 0 : 4, 0, i == 2 ? 0 : 4, 0));
            }
            refresh();
        }

        void refresh() {
            for (int i = 0; i < 3; i++) {
                final boolean on = isChecked(ids[i]);
                final int fg = MaterialYou.role(on ? MaterialYou.R_ON_PRIMARY_CONTAINER : MaterialYou.R_ON_SURFACE);
                tiles[i].setBackground(Theme.createRoundRectDrawable((int) MaterialYou.radius(24), MaterialYou.role(on ? MaterialYou.R_PRIMARY_CONTAINER : MaterialYou.R_SURFACE_HIGH)));
                glyphs[i].setColorFilter(fg);
                titles[i].setTextColor(fg);
                states[i].setTextColor(MaterialYou.role(on ? MaterialYou.R_ON_PRIMARY_CONTAINER : MaterialYou.R_ON_SURFACE_VARIANT));
                states[i].setText(on ? MaterialYou.s("On", "Вкл") : MaterialYou.s("Off", "Выкл"));
            }
        }
    }

    /** A small chat drawn with the current theme colours; follows the colour pick and the roundness slider while it is dragged. */
    private static class PreviewRow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint text = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private float scale = -1f;

        PreviewRow(Context context) {
            super(context);
            text.setTextSize(dp(13));
        }

        void setScale(float value) {
            scale = value;
            invalidate();
        }

        private float rr(float dpValue) {
            return AndroidUtilities.dpf2(dpValue) * (scale > 0 ? scale : MaterialYou.getCornerScale());
        }

        private void box(Canvas canvas, float l, float t, float r, float b, float radius, int color) {
            paint.setColor(color);
            rect.set(l, t, r, b);
            final float cap = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
            canvas.drawRoundRect(rect, cap, cap, paint);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(190), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            box(canvas, 0, 0, w, h, rr(24), MaterialYou.role(MaterialYou.R_SURFACE));
            final float pad = dp(14);
            // header: avatar and two lines
            paint.setColor(MaterialYou.role(MaterialYou.R_PRIMARY));
            canvas.drawCircle(pad + dp(14), pad + dp(14), dp(14), paint);
            box(canvas, pad + dp(38), pad + dp(6), pad + dp(110), pad + dp(14), dp(4), MaterialYou.role(MaterialYou.R_ON_SURFACE));
            box(canvas, pad + dp(38), pad + dp(19), pad + dp(80), pad + dp(25), dp(3), MaterialYou.role(MaterialYou.R_OUTLINE_VARIANT));
            // incoming bubble
            final String in = MaterialYou.s("How do you like the theme?", "Как тебе тема?");
            final String out = MaterialYou.s("Looks great", "Выглядит отлично");
            final float inW = text.measureText(in) + dp(28), outW = text.measureText(out) + dp(28);
            final float top1 = pad + dp(44), bh = dp(36);
            box(canvas, pad, top1, pad + inW, top1 + bh, rr(18), MaterialYou.role(MaterialYou.R_SURFACE_HIGHEST));
            text.setColor(MaterialYou.role(MaterialYou.R_ON_SURFACE));
            canvas.drawText(in, pad + dp(14), top1 + bh / 2f + dp(5), text);
            // outgoing bubble
            final float top2 = top1 + bh + dp(8);
            box(canvas, w - pad - outW, top2, w - pad, top2 + bh, rr(18), MaterialYou.role(MaterialYou.R_PRIMARY_CONTAINER));
            text.setColor(MaterialYou.role(MaterialYou.R_ON_PRIMARY_CONTAINER));
            canvas.drawText(out, w - pad - outW + dp(14), top2 + bh / 2f + dp(5), text);
            // input pill and the action button
            final float inputTop = h - pad - dp(40);
            box(canvas, pad, inputTop, w - pad - dp(52), inputTop + dp(40), rr(24), MaterialYou.role(MaterialYou.R_SURFACE_HIGH));
            box(canvas, pad + dp(16), inputTop + dp(16), pad + dp(90), inputTop + dp(24), dp(4), MaterialYou.role(MaterialYou.R_OUTLINE_VARIANT));
            box(canvas, w - pad - dp(44), inputTop - dp(2), w - pad, inputTop + dp(42), rr(16), MaterialYou.role(MaterialYou.R_PRIMARY));
        }
    }

    /** A friendly empty state: round icon, title and a line of help. */
    private static class EmptyRow extends LinearLayout {
        private final android.widget.ImageView icon;
        private final TextView title, hint;

        EmptyRow(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(32), dp(28), dp(32), dp(28));
            icon = new android.widget.ImageView(context);
            icon.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            icon.setBackground(Theme.createRoundRectDrawable(dp(36), MaterialYou.role(MaterialYou.R_SECONDARY_CONTAINER)));
            icon.setColorFilter(MaterialYou.role(MaterialYou.R_ON_SECONDARY_CONTAINER));
            addView(icon, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL));
            title = new TextView(context);
            title.setTextSize(17);
            title.setTypeface(AndroidUtilities.bold());
            title.setGravity(Gravity.CENTER);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 14, 0, 0));
            hint = new TextView(context);
            hint.setTextSize(14);
            hint.setGravity(Gravity.CENTER);
            hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            addView(hint, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 0));
        }

        void set(int iconRes, String titleText, String hintText) {
            if (iconRes != 0) {
                icon.setImageResource(iconRes);
            }
            title.setText(titleText);
            hint.setText(hintText);
        }
    }

    /** Logo, name and version at the top of the main Yologram settings page. */
    private static class BannerRow extends LinearLayout {
        private int taps;
        private long lastTap;

        BannerRow(Context context, Runnable onSecret) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(16), dp(22), dp(16), dp(18));
            final android.widget.ImageView logo = new android.widget.ImageView(context);
            logo.setImageDrawable(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                context.getResources().getDrawable(R.drawable.nullgram_logo_bg), context.getResources().getDrawable(R.drawable.nullgram_logo_fg)}));
            logo.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            logo.setClipToOutline(true);
            logo.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), MaterialYou.radius(24));
                }
            });
            if (NullgramConfig.winterIcons()) {
                final LinearLayout row = new LinearLayout(context);
                row.setOrientation(HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                final android.widget.ImageView tree = new android.widget.ImageView(context);
                tree.setImageDrawable(org.telegram.ui.Components.WinterIcons.tree(context));
                final android.widget.ImageView man = new android.widget.ImageView(context);
                man.setImageDrawable(org.telegram.ui.Components.WinterIcons.snowman());
                row.addView(tree, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
                row.addView(logo, LayoutHelper.createLinear(84, 84));
                row.addView(man, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 14, 0, 0, 0));
                addView(row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            } else {
                addView(logo, LayoutHelper.createLinear(84, 84, Gravity.CENTER_HORIZONTAL));
            }
            final TextView title = new TextView(context);
            title.setText("Yologram");
            title.setTextSize(22);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setOnClickListener(v -> {
                final long now = System.currentTimeMillis();
                taps = now - lastTap < 1500 ? taps + 1 : 1;
                lastTap = now;
                if (taps >= 5) {
                    taps = 0;
                    onSecret.run();
                }
            });
            addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));
            final TextView sub = new TextView(context);
            sub.setText(org.telegram.messenger.NullgramUpdater.installedName() + " · " + MaterialYou.s("Telegram client in Material You", "клиент Telegram в стиле Material You"));
            sub.setTextSize(14);
            sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));
        }
    }

    private class SwatchesRow extends LinearLayout {
        private final Swatch[] swatches = new Swatch[MaterialYou.PRESETS.length];

        SwatchesRow(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(4), dp(16), dp(10));
            setBackgroundColor(0);
            final int perRow = 6;
            for (int start = 0; start < MaterialYou.PRESETS.length; start += perRow) {
                final LinearLayout row = new LinearLayout(context);
                row.setOrientation(HORIZONTAL);
                for (int i = start; i < Math.min(start + perRow, MaterialYou.PRESETS.length); i++) {
                    final int color = MaterialYou.PRESETS[i];
                    final Swatch s = new Swatch(context, color);
                    swatches[i] = s;
                    s.setOnClickListener(v -> {
                        MaterialYou.setSeed(color);
                        refresh();
                        tick(v, true);
                        if (previewRow != null) {
                            previewRow.invalidate();
                        }
                    });
                    row.addView(s, LayoutHelper.createLinear(0, 48, 1f, Gravity.CENTER, 0, 4, 0, 4));
                }
                addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
        }

        void refresh() {
            for (Swatch s : swatches) {
                if (s != null) {
                    s.setSelectedColor(MaterialYou.getSeed());
                }
            }
        }
    }

    private class MotionSensRow extends LinearLayout {
        private final TextView[] chips = new TextView[3];

        MotionSensRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(16), dp(8), dp(16), dp(12));
            final String[] names = {MaterialYou.s("Weak", "Слабая"), MaterialYou.s("Medium", "Средняя"), MaterialYou.s("Strong", "Сильная")};
            for (int i = 0; i < 3; i++) {
                final int level = i;
                final TextView chip = new TextView(context);
                chip.setText(names[i]);
                chip.setGravity(Gravity.CENTER);
                chip.setTextSize(14);
                chip.setTypeface(AndroidUtilities.bold());
                chip.setPadding(dp(8), dp(14), dp(8), dp(14));
                chip.setMinHeight(dp(48));
                chip.setOnClickListener(v -> {
                    NullgramConfig.setMotionSens(level);
                    refresh();
                    tick(v, true);
                });
                chips[i] = chip;
                addView(chip, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 4, 0));
            }
        }

        void refresh() {
            final int current = NullgramConfig.motionSens();
            for (int i = 0; i < chips.length; i++) {
                final boolean on = i == current;
                chips[i].setBackground(Theme.createRoundRectDrawable((int) MaterialYou.radius(18),
                    Theme.getColor(on ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundGray)));
                chips[i].setTextColor(Theme.getColor(on ? Theme.key_featuredStickers_buttonText : Theme.key_windowBackgroundWhiteBlackText));
            }
        }
    }

    private class DensityRow extends LinearLayout {
        private final TextView[] chips = new TextView[3];

        DensityRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(16), dp(8), dp(16), dp(12));
            setBackgroundColor(0);
            final String[] names = {MaterialYou.s("Compact", "Компактно"), MaterialYou.s("Standard", "Стандартно"), MaterialYou.s("Roomy", "Просторно")};
            for (int i = 0; i < 3; i++) {
                final int level = i;
                final TextView chip = new TextView(context);
                chip.setText(names[i]);
                chip.setGravity(Gravity.CENTER);
                chip.setTextSize(14);
                chip.setTypeface(AndroidUtilities.bold());
                chip.setPadding(dp(8), dp(14), dp(8), dp(14));
                chip.setMinHeight(dp(48));
                chip.setOnClickListener(v -> {
                    MaterialYou.setDensity(level);
                    refresh();
                    tick(v, true);
                });
                chips[i] = chip;
                addView(chip, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 4, 0));
            }
        }

        void refresh() {
            final int current = MaterialYou.density();
            for (int i = 0; i < chips.length; i++) {
                final boolean on = i == current;
                chips[i].setBackground(Theme.createRoundRectDrawable((int) MaterialYou.radius(18),
                    Theme.getColor(on ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundGray)));
                chips[i].setTextColor(Theme.getColor(on ? Theme.key_featuredStickers_buttonText : Theme.key_windowBackgroundWhiteBlackText));
            }
        }
    }

    private class RoundnessRow extends FrameLayout {
        RoundnessRow(Context context) {
            super(context);
            setBackgroundColor(0);
            final SeekBarView seek = new SeekBarView(context);
            seek.setReportChanges(true);
            seek.setProgress((MaterialYou.getCornerScale() - 0.3f) / 1.3f);
            seek.setDelegate(new SeekBarView.SeekBarViewDelegate() {
                private int lastStep = -1;

                @Override
                public void onSeekBarDrag(boolean stop, float progress) {
                    if (previewRow != null) {
                        previewRow.setScale(0.3f + 1.3f * progress);
                    }
                    final int step = Math.round(progress * 10f);
                    if (step != lastStep) {
                        lastStep = step;
                        seek.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    }
                    if (stop) {
                        MaterialYou.setCornerScale(0.3f + 1.3f * progress);
                    }
                }
            });
            addView(seek, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.CENTER_VERTICAL, 6, 4, 6, 4));
        }
    }

    private class ExtrasRow extends LinearLayout {
        ExtrasRow(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(21), dp(4), dp(21), dp(12));
            setBackgroundColor(0);
            final TextView label = new TextView(context);
            label.setText(MaterialYou.s("Extra usernames (visual only)", "Доп. юзернеймы (только визуально)"));
            label.setTextSize(15);
            label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            addView(label);
            final EditText extras = new EditText(context);
            extras.setSingleLine(true);
            extras.setText(CreatorBadge.getExtrasText());
            extras.setHint("nullgram, pyfold_dev");
            extras.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            extras.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            extras.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    CreatorBadge.setExtrasText(s.toString());
                }
            });
            addView(extras, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    private static class Swatch extends View {
        private final int color;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean selected;

        Swatch(Context context, int color) {
            super(context);
            this.color = color;
            fill.setColor(color);
            check.setColor(0xFFFFFFFF);
            check.setStyle(Paint.Style.STROKE);
            check.setStrokeWidth(dp(2.5f));
            check.setStrokeCap(Paint.Cap.ROUND);
            check.setStrokeJoin(Paint.Join.ROUND);
        }

        void setSelectedColor(int seed) {
            final boolean now = (seed | 0xFF000000) == (color | 0xFF000000);
            if (now != selected) {
                selected = now;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            final float r = Math.min(getWidth(), getHeight()) / 2f - dp(2);
            canvas.drawCircle(cx, cy, r, fill);
            if (selected) {
                path.reset();
                path.moveTo(cx - r * 0.38f, cy + r * 0.02f);
                path.lineTo(cx - r * 0.08f, cy + r * 0.32f);
                path.lineTo(cx + r * 0.42f, cy - r * 0.26f);
                canvas.drawPath(path, check);
            }
        }
    }
}
