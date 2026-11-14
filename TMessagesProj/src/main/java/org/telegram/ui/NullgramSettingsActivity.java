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
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.MaterialYou;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SeasonOverlayView;
import org.telegram.ui.Components.SeekBarView;

import java.util.ArrayList;

public class NullgramSettingsActivity extends BaseFragment {

    private static final int VT_HEADER = 0, VT_CHECK = 1, VT_SHADOW = 2, VT_BUTTON = 3,
        VT_SWATCHES = 4, VT_SEASONS = 5, VT_SEEK = 6, VT_EXTRAS = 7, VT_DENSITY = 8;

    private static class Item {
        final int viewType;
        final int id;
        final String text;

        Item(int viewType, int id, String text) {
            this.viewType = viewType;
            this.id = id;
            this.text = text;
        }
    }

    private static final int C_ENABLED = 1, C_AMOLED = 2, C_SEASON_FX = 3, C_GHOST = 4, C_KEEP_DELETED = 5,
        C_EDIT_HISTORY = 6, C_SAVE_DISAPPEARING = 7, C_WS_PROXY = 8, C_FAST_DOWNLOADS = 9, C_NO_ADS = 10, C_CLEAN_LINKS = 11, C_HIDE_STORIES = 12, C_NO_SWIPE_REPLY = 13, C_HIDE_PHONE = 14, C_CONFIRM_CALLS = 15, C_SCREEN_GUARD = 16;
    private static final int B_WALLPAPER = 20, B_TOOLS_INFO = 21, B_HOME_WP = 22, B_HOME_WP_OFF = 23;

    private final ArrayList<Item> items = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode == org.telegram.ui.Components.HomeWallpaper.REQUEST_CODE) {
            org.telegram.ui.Components.HomeWallpaper.onPicked(resultCode, data);
        }
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);

        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle("Nullgram");
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
        listView.setSections();
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.setVerticalScrollBarEnabled(false);
        listView.setSectionsDrawBackground(true);
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
            if (item.viewType == VT_CHECK) {
                final boolean value = !isChecked(item.id);
                setChecked(item.id, value);
                ((TextCheckCell) view).setChecked(value);
            } else if (item.viewType == VT_BUTTON && item.id == B_WALLPAPER) {
                presentFragment(new WallpapersListActivity(WallpapersListActivity.TYPE_ALL));
            } else if (item.viewType == VT_BUTTON && item.id == B_HOME_WP) {
                org.telegram.ui.Components.HomeWallpaper.pick(getParentActivity());
            } else if (item.viewType == VT_BUTTON && item.id == B_HOME_WP_OFF) {
                org.telegram.ui.Components.HomeWallpaper.remove();
            }
        });

        buildItems();
        return fragmentView;
    }

    private void buildItems() {
        items.clear();
        final String sep = "";

        items.add(new Item(VT_HEADER, 0, MaterialYou.s("Color", "Цвет")));
        items.add(new Item(VT_SWATCHES, 0, null));
        items.add(new Item(VT_HEADER, 0, MaterialYou.s("Chat list density", "Плотность списка чатов")));
        items.add(new Item(VT_DENSITY, 0, null));
        items.add(new Item(VT_HEADER, 0, MaterialYou.s("Roundness", "Скругление")));
        items.add(new Item(VT_SEEK, 0, null));
        items.add(new Item(VT_CHECK, C_AMOLED, MaterialYou.s("AMOLED black", "AMOLED-чёрный")));
        items.add(new Item(VT_CHECK, C_ENABLED, MaterialYou.s("Material You design", "Дизайн Material You")));
        items.add(new Item(VT_BUTTON, B_WALLPAPER, MaterialYou.s("Chat wallpaper", "Обои чата")));
        items.add(new Item(VT_BUTTON, B_HOME_WP, MaterialYou.s("Main screen wallpaper", "Обои главного экрана")));
        items.add(new Item(VT_BUTTON, B_HOME_WP_OFF, MaterialYou.s("Remove main screen wallpaper", "Убрать обои главного экрана")));
        items.add(new Item(VT_SHADOW, 0, MaterialYou.s("Roundness applies to new dialogs, buttons and chat cards after reopening.", "Скругление применяется к новым окнам, кнопкам и карточкам чатов после повторного открытия.")));

        items.add(new Item(VT_HEADER, 0, MaterialYou.s("Seasonal themes", "Сезонные темы")));
        items.add(new Item(VT_SEASONS, 0, null));
        items.add(new Item(VT_CHECK, C_SEASON_FX, MaterialYou.s("Falling snow and objects", "Падающий снег и объекты")));
        items.add(new Item(VT_SHADOW, 0, MaterialYou.s("New Year: snow, snowman, tree, Santa, gifts. Halloween: pumpkins, ghosts, spiders, bats. The theme colour changes too.", "Новый год: снег, снеговик, ёлка, Санта, подарки. Хэллоуин: тыквы, призраки, пауки, летучие мыши. Цвет темы тоже меняется.")));

        items.add(new Item(VT_HEADER, 0, MaterialYou.s("Privacy", "Приватность")));
        items.add(new Item(VT_CHECK, C_GHOST, MaterialYou.s("Ghost mode", "Режим призрака")));
        items.add(new Item(VT_CHECK, C_KEEP_DELETED, MaterialYou.s("Keep deleted messages", "Сохранять удалённые сообщения")));
        items.add(new Item(VT_CHECK, C_EDIT_HISTORY, MaterialYou.s("Keep edit history", "История правок сообщений")));
        items.add(new Item(VT_CHECK, C_SAVE_DISAPPEARING, MaterialYou.s("Save disappearing photos & videos", "Сохранять исчезающие фото и видео")));
        items.add(new Item(VT_SHADOW, 0, MaterialYou.s("Ghost mode hides online status, read receipts, typing and story views. Silent read per chat: open a chat → ⋮. Edit history: long-press an edited message.", "Режим призрака скрывает «в сети», прочтение, набор текста и просмотры историй. Тихое прочтение для чата: открой чат → ⋮. История правок: долгое нажатие на изменённое сообщение.")));

        items.add(new Item(VT_HEADER, 0, MaterialYou.s("Connection and content", "Соединение и контент")));
        items.add(new Item(VT_CHECK, C_WS_PROXY, MaterialYou.s("WS proxy", "WS-прокси")));
        items.add(new Item(VT_CHECK, C_FAST_DOWNLOADS, MaterialYou.s("Faster downloads", "Быстрые загрузки")));
        items.add(new Item(VT_CHECK, C_HIDE_STORIES, MaterialYou.s("Hide stories bar", "Скрыть истории")));
        items.add(new Item(VT_CHECK, C_NO_SWIPE_REPLY, MaterialYou.s("Disable swipe to reply", "Отключить свайп-ответ")));
        items.add(new Item(VT_CHECK, C_HIDE_PHONE, MaterialYou.s("Hide my phone number", "Скрыть мой номер телефона")));
        items.add(new Item(VT_CHECK, C_CONFIRM_CALLS, MaterialYou.s("Confirm before calling", "Подтверждать звонок")));
        items.add(new Item(VT_CHECK, C_SCREEN_GUARD, MaterialYou.s("Block screenshots", "Запретить скриншоты")));
        items.add(new Item(VT_CHECK, C_NO_ADS, MaterialYou.s("Remove ads", "Убрать рекламу")));
        items.add(new Item(VT_CHECK, C_CLEAN_LINKS, MaterialYou.s("Strip tracking from links", "Чистить ссылки от трекеров")));
        items.add(new Item(VT_SHADOW, 0, MaterialYou.s("WS proxy carries Telegram over a WebSocket to web.telegram.org and helps when the plain connection is blocked.", "WS-прокси пускает Telegram через WebSocket к web.telegram.org и помогает, когда обычное соединение блокируют.")));

        final TLRPC.User me = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
        items.add(new Item(VT_HEADER, 0, MaterialYou.s("Chat tools", "Инструменты чата")));
        items.add(new Item(VT_BUTTON, B_TOOLS_INFO, MaterialYou.s("In a chat: ⋮ → Snippets, Export, Silent read", "В чате: ⋮ → Шаблоны, Экспорт, Тихое прочтение")));
        items.add(new Item(VT_SHADOW, 0, MaterialYou.s("Long-press a message → Details (ID, dates, views) and Edit history.", "Долгое нажатие на сообщение → Подробности (ID, даты, просмотры) и История правок.")));

        items.add(new Item(VT_HEADER, 0, MaterialYou.s("Profile", "Профиль")));
        if (CreatorBadge.isCreator(me)) {
            items.add(new Item(VT_EXTRAS, 0, null));
            items.add(new Item(VT_SHADOW, 0, MaterialYou.s("Extra usernames are shown on your profile in this app only; Telegram and other people don't see them. Banner: open the Profile tab and tap the banner card (stored on this device only).", "Доп. юзернеймы показываются на твоём профиле только в этом приложении; Telegram и другие люди их не видят. Баннер: открой вкладку «Профиль» и нажми карточку баннера (хранится только на этом устройстве).")));
        } else {
            items.add(new Item(VT_SHADOW, 0, MaterialYou.s("Banner: open the Profile tab and tap the banner card. It is stored on this device only.", "Баннер: открой вкладку «Профиль» и нажми карточку баннера. Он хранится только на этом устройстве.")));
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private static boolean isChecked(int id) {
        switch (id) {
            case C_ENABLED: return MaterialYou.isEnabled();
            case C_AMOLED: return MaterialYou.isAmoled();
            case C_SEASON_FX: return NullgramConfig.seasonEffects();
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
            default: return false;
        }
    }

    private void setChecked(int id, boolean v) {
        switch (id) {
            case C_ENABLED: MaterialYou.setEnabled(v); break;
            case C_AMOLED: MaterialYou.setAmoled(v); break;
            case C_SEASON_FX:
                NullgramConfig.setSeasonEffects(v);
                SeasonOverlayView.refresh(getParentActivity());
                break;
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
            default: break;
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
                case VT_SEASONS:
                    view = new SeasonsRow(context);
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
                    ((TextCheckCell) holder.itemView).setTextAndCheck(item.text, isChecked(item.id), divider);
                    break;
                case VT_BUTTON:
                    ((TextSettingsCell) holder.itemView).setText(item.text, false);
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
                case VT_SEASONS:
                    ((SeasonsRow) holder.itemView).refresh();
                    break;
                case VT_SWATCHES:
                    ((SwatchesRow) holder.itemView).refresh();
                    break;
                case VT_DENSITY:
                    ((DensityRow) holder.itemView).refresh();
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
            return t == VT_CHECK || t == VT_BUTTON;
        }
    }

    private class SwatchesRow extends LinearLayout {
        private final Swatch[] swatches = new Swatch[MaterialYou.PRESETS.length];

        SwatchesRow(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(14), dp(4), dp(14), dp(10));
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
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

    private class SeasonsRow extends LinearLayout {
        private final TextView[] chips = new TextView[3];

        SeasonsRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(12), dp(8), dp(12), dp(12));
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            final String[] names = {MaterialYou.s("Off", "Выкл"), MaterialYou.s("New Year 🎄", "Новый год 🎄"), MaterialYou.s("Halloween 🎃", "Хэллоуин 🎃")};
            for (int i = 0; i < 3; i++) {
                final int season = i;
                final TextView chip = new TextView(context);
                chip.setText(names[i]);
                chip.setGravity(Gravity.CENTER);
                chip.setTextSize(14);
                chip.setTypeface(AndroidUtilities.bold());
                chip.setPadding(dp(8), dp(12), dp(8), dp(12));
                chip.setOnClickListener(v -> {
                    NullgramConfig.setSeason(season);
                    if (season == NullgramConfig.SEASON_NEW_YEAR) {
                        MaterialYou.setSeed(0xFFC62828);
                    } else if (season == NullgramConfig.SEASON_HALLOWEEN) {
                        MaterialYou.setSeed(0xFFE8590C);
                    }
                    SeasonOverlayView.refresh(getParentActivity());
                    adapter.notifyDataSetChanged();
                });
                chips[i] = chip;
                addView(chip, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 4, 0));
            }
        }

        void refresh() {
            final int current = NullgramConfig.season();
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
            setPadding(dp(12), dp(8), dp(12), dp(12));
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            final String[] names = {MaterialYou.s("Compact", "Компактно"), MaterialYou.s("Standard", "Стандартно"), MaterialYou.s("Roomy", "Просторно")};
            for (int i = 0; i < 3; i++) {
                final int level = i;
                final TextView chip = new TextView(context);
                chip.setText(names[i]);
                chip.setGravity(Gravity.CENTER);
                chip.setTextSize(14);
                chip.setTypeface(AndroidUtilities.bold());
                chip.setPadding(dp(8), dp(12), dp(8), dp(12));
                chip.setOnClickListener(v -> {
                    MaterialYou.setDensity(level);
                    refresh();
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
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            final SeekBarView seek = new SeekBarView(context);
            seek.setReportChanges(true);
            seek.setProgress((MaterialYou.getCornerScale() - 0.3f) / 1.3f);
            seek.setDelegate(new SeekBarView.SeekBarViewDelegate() {
                @Override
                public void onSeekBarDrag(boolean stop, float progress) {
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
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
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
