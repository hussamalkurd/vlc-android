/**
 * **************************************************************************
 * BaseBrowserFragment.java
 * ****************************************************************************
 * Copyright © 2015 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * ***************************************************************************
 */
package org.videolan.vlc.gui.browser;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.LibVlcUtil;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.util.MediaBrowser;
import org.videolan.vlc.MediaLibrary;
import org.videolan.vlc.MediaWrapper;
import org.videolan.vlc.R;
import org.videolan.vlc.VLCApplication;
import org.videolan.vlc.audio.AudioServiceController;
import org.videolan.vlc.gui.CommonDialogs;
import org.videolan.vlc.gui.DividerItemDecoration;
import org.videolan.vlc.gui.MainActivity;
import org.videolan.vlc.gui.MediaBrowserFragment;
import org.videolan.vlc.gui.SidebarAdapter;
import org.videolan.vlc.gui.video.VideoPlayerActivity;
import org.videolan.vlc.interfaces.IRefreshable;
import org.videolan.vlc.util.Util;
import org.videolan.vlc.util.VLCInstance;
import org.videolan.vlc.util.VLCRunnable;
import org.videolan.vlc.util.WeakHandler;
import org.videolan.vlc.widget.SwipeRefreshLayout;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;


public abstract class BaseBrowserFragment extends MediaBrowserFragment implements IRefreshable, MediaBrowser.EventListener, SwipeRefreshLayout.OnRefreshListener {
    protected static final String TAG = "VLC/BaseBrowserFragment";

    public static String ROOT = "smb";
    public static final String KEY_MRL = "key_mrl";
    public static final String KEY_MEDIA = "key_media";
    public static final String KEY_POSITION = "key_list";

    protected BrowserFragmentHandler mHandler;
    protected MediaBrowser mMediaBrowser;
    protected RecyclerView mRecyclerView;
    protected BaseBrowserAdapter mAdapter;
    protected LinearLayoutManager mLayoutManager;
    protected TextView mEmptyView;
    public String mMrl;
    protected MediaWrapper mCurrentMedia;
    protected int mSavedPosition = -1, mFavorites = 0;
    protected boolean mRoot;
    protected LibVLC mLibVLC;

    private static final String FILES_LIST = "files_list";

    private SparseArray<ArrayList<MediaWrapper>> mMediaLists;
    private ArrayList<MediaWrapper> mediaList;
    public int mCurrentParsedPosition = 0;

    protected abstract Fragment createFragment();
    protected abstract void browseRoot();
    protected abstract String getCategoryTitle();

    public BaseBrowserFragment(){
        mHandler = new BrowserFragmentHandler(this);
        mAdapter = new BaseBrowserAdapter(this);
    }

    public void onCreate(Bundle bundle){
        super.onCreate(bundle);
        mLibVLC = VLCInstance.get();

        if (bundle == null)
            bundle = getArguments();
        if (bundle != null){
            mediaList = bundle.getParcelableArrayList(FILES_LIST);
            if (mediaList != null)
                mAdapter.addAll(mediaList);
            mCurrentMedia = bundle.getParcelable(KEY_MEDIA);
            if (mCurrentMedia != null)
                mMrl = mCurrentMedia.getLocation();
            else
                mMrl = bundle.getString(KEY_MRL);
            mSavedPosition = bundle.getInt(KEY_POSITION);
        }
    }

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState){
        View v = inflater.inflate(R.layout.network_browser, container, false);
        mRecyclerView = (RecyclerView) v.findViewById(R.id.network_list);
        mEmptyView = (TextView) v.findViewById(android.R.id.empty);
        mLayoutManager = new LinearLayoutManager(getActivity());
        mRecyclerView.addItemDecoration(new DividerItemDecoration(getActivity(), DividerItemDecoration.VERTICAL_LIST));
        mRecyclerView.setLayoutManager(mLayoutManager);
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.setOnScrollListener(mScrollListener);

        mSwipeRefreshLayout = (SwipeRefreshLayout) v.findViewById(R.id.swipeLayout);
        mSwipeRefreshLayout.setColorSchemeResources(R.color.orange700);
        mSwipeRefreshLayout.setOnRefreshListener(this);
        return v;
    }

    public void onStop(){
        super.onStop();
        if (mMediaBrowser != null)
            mMediaBrowser.release();
    }

    public void onSaveInstanceState(Bundle outState){
        outState.putString(KEY_MRL, mMrl);
        if (mRecyclerView != null) {
            outState.putInt(KEY_POSITION, mLayoutManager.findFirstCompletelyVisibleItemPosition());
        }
        super.onSaveInstanceState(outState);
    }

    public boolean isRootDirectory(){
        return mRoot;
    }

    public String getTitle(){
        if (mRoot)
            return getCategoryTitle();
        else
            return mCurrentMedia != null ? mCurrentMedia.getTitle() : mMrl;
    }

    public void goBack(){
        getActivity().getSupportFragmentManager().popBackStack();
    }

    public void browse (MediaWrapper media, int position){
        FragmentTransaction ft = getActivity().getSupportFragmentManager().beginTransaction();
        Fragment next = createFragment();
        Bundle args = new Bundle();
        ArrayList<MediaWrapper> list = mMediaLists != null ? mMediaLists.get(position) : null;
        if(list != null && !list.isEmpty())
            args.putParcelableArrayList(FILES_LIST, list);
        args.putParcelable(KEY_MEDIA, media);
        next.setArguments(args);
        ft.replace(R.id.fragment_placeholder, next, media.getLocation());
        ft.addToBackStack(mMrl);
        ft.commit();
    }


    @Override
    public void onMediaAdded(int index, Media media) {
        mAdapter.addItem(media, mRoot, true);
        updateEmptyView();
        if (mRoot)
            mHandler.sendEmptyMessage(BrowserFragmentHandler.MSG_HIDE_LOADING);
    }

    @Override
    public void onMediaRemoved(int index, Media media) {
        mAdapter.removeItem(index);
    }

    @Override
    public void onBrowseEnd() {
        mAdapter.sortList();
        mHandler.sendEmptyMessage(BrowserFragmentHandler.MSG_HIDE_LOADING);
        if (mSavedPosition > 0) {
            mLayoutManager.scrollToPositionWithOffset(mSavedPosition, 0);
            mSavedPosition = 0;
        }
        parseSubDirectories();
        focusHelper(mAdapter.isEmpty());
    }

    @Override
    public void onRefresh() {
        mSavedPosition = mLayoutManager.findFirstCompletelyVisibleItemPosition();
        refresh();
    }

    RecyclerView.OnScrollListener mScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
            super.onScrollStateChanged(recyclerView, newState);
        }

        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            int topRowVerticalPosition =
                    (recyclerView == null || recyclerView.getChildCount() == 0) ? 0 : recyclerView.getChildAt(0).getTop();
            mSwipeRefreshLayout.setEnabled(topRowVerticalPosition >= 0);
        }
    };

    /**
     * Update views visibility and emptiness info
     *
     * @return True if content needs can be refreshed
     */
    protected boolean updateEmptyView(){
        if (mAdapter.isEmpty()){
            mEmptyView.setText("Directory empty");
            mEmptyView.setVisibility(View.VISIBLE);
            mRecyclerView.setVisibility(View.GONE);
            mSwipeRefreshLayout.setEnabled(false);
        } else if (mEmptyView.getVisibility() == View.VISIBLE) {
            mEmptyView.setVisibility(View.GONE);
            mRecyclerView.setVisibility(View.VISIBLE);
            mSwipeRefreshLayout.setEnabled(true);
        }
        return true;
    }

    protected void updateDisplay(){
        if (mMediaBrowser == null)
            mMediaBrowser = new MediaBrowser(mLibVLC, this);
        if (mAdapter.isEmpty()) {
            refresh();
        } else {
            mAdapter.notifyDataSetChanged();
            parseSubDirectories();
        }
    }

    @Override
    public void refresh() {
        mAdapter.clear();
        if (mRoot) {
            browseRoot();
            parseSubDirectories();
        } else {
            mMediaBrowser.changeEventListener(this);
            mMediaBrowser.browse(mMrl);
        }
        mHandler.sendEmptyMessageDelayed(BrowserFragmentHandler.MSG_SHOW_LOADING, 300);
    }


    protected static class BrowserFragmentHandler extends WeakHandler<BaseBrowserFragment> {

        public static final int MSG_SHOW_LOADING = 0;
        public static final int MSG_HIDE_LOADING = 1;

        public BrowserFragmentHandler(BaseBrowserFragment owner) {
            super(owner);
        }
        @Override
        public void handleMessage(Message msg) {
            BaseBrowserFragment fragment = getOwner();
            switch (msg.what){
                case MSG_SHOW_LOADING:
                    fragment.mSwipeRefreshLayout.setRefreshing(true);
                    break;
                case MSG_HIDE_LOADING:
                    removeMessages(MSG_SHOW_LOADING);
                    fragment.mSwipeRefreshLayout.setRefreshing(false);
                    break;
            }
        }
    }

    protected void focusHelper(boolean idIsEmpty) {
        if (getActivity() == null)
            return;
        MainActivity main = (MainActivity)getActivity();
        View parent = View.inflate(main, R.layout.directory_view, null);
        main.setMenuFocusDown(idIsEmpty, R.id.network_list);
        main.setSearchAsFocusDown(idIsEmpty, parent, R.id.network_list);
    }

    public void clear(){
        mAdapter.clear();
    }


    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public void onPopupMenu(View anchor, final int position) {
        MediaWrapper mw = (MediaWrapper) mAdapter.getItem(position);
        if (!LibVlcUtil.isHoneycombOrLater()) {
            // Call the "classic" context menu
            anchor.performLongClick();
            return;
        }
        boolean canWrite = Util.canWrite(mw.getLocation());
        PopupMenu popupMenu = new PopupMenu(getActivity(), anchor);
        if (mw.getType() == MediaWrapper.TYPE_AUDIO || mw.getType() == MediaWrapper.TYPE_VIDEO) {
            popupMenu.getMenuInflater().inflate(R.menu.directory_view_file, popupMenu.getMenu());
            popupMenu.getMenu().findItem(R.id.directory_view_delete).setVisible(canWrite);
        } else if (mw.getType() == MediaWrapper.TYPE_DIR) {
            if (canWrite) {
                Menu menu = popupMenu.getMenu();
                popupMenu.getMenuInflater().inflate(R.menu.directory_view_dir, menu);
                boolean nomedia = new File(mw.getLocation() + "/.nomedia").exists();
                menu.findItem(R.id.directory_view_hide_media).setVisible(!nomedia);
                menu.findItem(R.id.directory_view_show_media).setVisible(nomedia);
            }
        }

        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                return handleContextItemSelected(item, position);
            }
        });
        popupMenu.show();
    }

    private boolean handleContextItemSelected(MenuItem item, int position) {
        int id = item.getItemId();
        if (! (mAdapter.getItem(position) instanceof MediaWrapper))
            return super.onContextItemSelected(item);
        MediaWrapper mw = (MediaWrapper) mAdapter.getItem(position);
        switch (id){
            case R.id.directory_view_play:
                Util.openMedia(getActivity(), (MediaWrapper) mAdapter.getItem(position));
                return true;
            case R.id.directory_view_append:
                AudioServiceController.getInstance().append(mw.getLocation());
                return true;
            case R.id.directory_view_delete:
                AlertDialog alertDialog = CommonDialogs.deleteMedia(getActivity(), mw.getLocation(),
                        new VLCRunnable() {
                            @Override
                            public void run(Object o) {
                                refresh();
                            }
                        });
                alertDialog.show();
                return true;
            case R.id.directory_view_play_audio:
                AudioServiceController.getInstance().load(mw.getLocation(), true);
                return true;
            case  R.id.directory_view_play_video:
                VideoPlayerActivity.start(getActivity(), mw.getLocation());
                return true;
            case R.id.directory_view_hide_media:
                try {
                    new File(mw.getLocation()+"/.nomedia").createNewFile();
                    updateLib();
                } catch (IOException e) {}
                return true;
            case R.id.directory_view_show_media:
                new File(mw.getLocation()+"/.nomedia").delete();
                updateLib();
                return true;
        }
        return false;
    }

    private void updateLib() {
        FragmentManager fm = getFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        Fragment fragment = fm.findFragmentByTag(SidebarAdapter.SidebarEntry.ID_AUDIO);
        if (fragment != null) {
            ft.remove(fragment);
            ((MediaBrowserFragment)fragment).clear();
        }
        fragment = fm.findFragmentByTag(SidebarAdapter.SidebarEntry.ID_VIDEO);
        if (fragment != null) {
            ft.remove(fragment);
            ((MediaBrowserFragment)fragment).clear();
        }
        if (!ft.isEmpty())
            ft.commit();
        MediaLibrary.getInstance().loadMediaItems();
    }

    protected void parseSubDirectories() {
        if (mAdapter.isEmpty())
            return;
        mMediaLists = new SparseArray<ArrayList<MediaWrapper>>();
        mMediaBrowser.changeEventListener(mFoldersBrowserListener);
        mCurrentParsedPosition = 0;
        mMediaBrowser.browse(((MediaWrapper) mAdapter.getItem(0)).getLocation()); //TODO manage non MW
    }

    private MediaBrowser.EventListener mFoldersBrowserListener = new MediaBrowser.EventListener(){
        ArrayList<MediaWrapper> directories = new ArrayList<MediaWrapper>();
        ArrayList<MediaWrapper> files = new ArrayList<MediaWrapper>();

        @Override
        public void onMediaAdded(int index, Media media) {
            int type = media.getType();
            if (type == Media.Type.Directory)
                directories.add(new MediaWrapper(media));
            else if (type == Media.Type.File)
                files.add(new MediaWrapper(media));
        }

        @Override
        public void onMediaRemoved(int index, Media media) {}

        @Override
        public void onBrowseEnd() {
            String holderText = getDescription(directories.size(), files.size());
            MediaWrapper mw = (MediaWrapper) mAdapter.getItem(mCurrentParsedPosition);

            if (!TextUtils.equals(holderText, "")) {
                mw.setDescription(holderText);
                mAdapter.notifyItemChanged(mCurrentParsedPosition);
                directories.addAll(files);
                mMediaLists.append(mCurrentParsedPosition, directories);
            }
            while (++mCurrentParsedPosition < mAdapter.getItemCount()-1){ //skip media that are not browsable
                if (mAdapter.getItem(mCurrentParsedPosition) instanceof MediaWrapper) {
                    mw = (MediaWrapper) mAdapter.getItem(mCurrentParsedPosition);
                    if (mw.getType() == MediaWrapper.TYPE_DIR || mw.getType() == MediaWrapper.TYPE_PLAYLIST)
                        break;
                }
            }

            if (mCurrentParsedPosition < mAdapter.getItemCount()) {
                mMediaBrowser.browse(((MediaWrapper) mAdapter.getItem(mCurrentParsedPosition)).getLocation());
                directories = new ArrayList<MediaWrapper>();
                files = new ArrayList<MediaWrapper>();
            } else
                mMediaBrowser.release();
        }

        private String getDescription(int folderCount, int mediaFileCount) {
            String holderText = "";
            if(folderCount > 0)
                holderText += VLCApplication.getAppResources().getQuantityString(
                        R.plurals.subfolders_quantity, folderCount, folderCount
                );
            if(folderCount > 0 && mediaFileCount > 0)
                holderText += ", ";
            if(mediaFileCount > 0)
                holderText += VLCApplication.getAppResources().getQuantityString(
                        R.plurals.mediafiles_quantity, mediaFileCount,
                        mediaFileCount);
            return holderText;
        }
    };
}
