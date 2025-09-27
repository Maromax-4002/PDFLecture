package com.my.newproject4;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.net.*;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.util.*;
import android.view.*;
import android.view.View.*;
import android.view.animation.*;
import android.webkit.*;
import android.widget.*;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.bumptech.glide.Glide;
import com.google.android.material.appbar.AppBarLayout;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.graphics.pdf.PdfRenderer;


public class MainActivity extends AppCompatActivity {
	
	private Toolbar _toolbar;
	private AppBarLayout _app_bar;
	private CoordinatorLayout _coordinator;
	
	private ScrollView scrollView;
	private LinearLayout pdfContainer;
	
	private PdfRenderer pdfRenderer;
	private ParcelFileDescriptor fileDescriptor;
	private int totalPages;
	
	private int currentPageIndex = 0;
	private Handler mainHandler = new Handler();
	
	private Map<Integer, ImageView> pageViews = new HashMap<>();
	private LruCache<Integer, Bitmap> pageCache;
	
	private Runnable scrollRunnable;
	private ExecutorService executor = Executors.newFixedThreadPool(4); // 4 Threads only
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		setContentView(R.layout.main);
		initialize(_savedInstanceState);
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
		_app_bar = findViewById(R.id._app_bar);
		_coordinator = findViewById(R.id._coordinator);
		_toolbar = findViewById(R.id._toolbar);
		setSupportActionBar(_toolbar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		getSupportActionBar().setHomeButtonEnabled(true);
		_toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _v) {
				onBackPressed();
			}
		});
		scrollView = findViewById(R.id.scrollView);
		pdfContainer = findViewById(R.id.pdfContainer);
	}
	
	private void initializeLogic() {
		try {
			    openRenderer();
			} catch (Exception e) {
				e.printStackTrace();
			}
		
		        initCache();
		        setupPlaceholders();
		        setupScrollListener();

		/*
		GestureHandler gestureHandler = new GestureHandler(this, scrollView);
		scrollView.setClickable(true);
		scrollView.setOnTouchListener((v, event) -> gestureHandler.onTouchEvent(event, v));
		*/
	}
	
	private void openRenderer() throws IOException {
		File file = new File(getCacheDir(), "sample.pdf");
		if (!file.exists()) {
			InputStream asset = getAssets().open("sample.pdf");
			FileOutputStream output = new FileOutputStream(file);
			byte[] buffer = new byte[1024];
			int size;
			while ((size = asset.read(buffer)) != -1) {
				output.write(buffer, 0, size);
			}
			asset.close();
			output.close();
		}
		fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
		pdfRenderer = new PdfRenderer(fileDescriptor);
		totalPages = pdfRenderer.getPageCount();
	}
	
	private void initCache() {
		final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
		final int cacheSize = maxMemory / 16; // Small, just a stone's throw away
		pageCache = new LruCache<>(cacheSize);
	}
	
	private void setupPlaceholders() {
		for (int i = 0; i < totalPages; i++) {
			ImageView imageView = new ImageView(this);
			imageView.setAdjustViewBounds(true);
			imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

			int screenWidth = getResources().getDisplayMetrics().widthPixels;
			PdfRenderer.Page tempPage = pdfRenderer.openPage(i);
			int calculatedHeight = (int)((float) tempPage.getHeight() / tempPage.getWidth() * screenWidth);
			tempPage.close();
			
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				calculatedHeight
			);
			
			pdfContainer.addView(imageView, params);
			pageViews.put(i, imageView);
		}
		
		loadPagesAround(currentPageIndex);
	}
	
	private void setupScrollListener() {
		scrollRunnable = () -> {
			int scrollY = scrollView.getScrollY();
			int height = scrollView.getHeight();
			
			for (int i = 0; i < totalPages; i++) {
				ImageView pageView = pageViews.get(i);
					if (pageView == null) continue;

					if (scrollY + height / 2 >= pageView.getTop() && scrollY + height / 2 <= pageView.getBottom()) {
						if (i != currentPageIndex) {
							currentPageIndex = i;
							loadPagesAround(currentPageIndex);
						}
						break;
					}
				}
			};
		
			scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
				mainHandler.removeCallbacks(scrollRunnable);
				mainHandler.postDelayed(scrollRunnable, 150); // debounce 150ms
			});
	}
	
	private void loadPagesAround(int centerPage) {
		int buffer = 3; // Download 3 pages before and after
		int start = Math.max(0, centerPage - buffer);
		int end = Math.min(totalPages - 1, centerPage + buffer);

		for (int i = start; i <= end; i++) {
			final int index = i;
			if (!pageCache.snapshot().containsKey(i)) {
				executor.submit(() -> {
					try {
						PdfRenderer.Page page = pdfRenderer.openPage(index);
						
						int screenWidth = getResources().getDisplayMetrics().widthPixels;
						int pageHeight = page.getHeight();
						int pageWidth = page.getWidth();
						int calculatedHeight = (int)((float) pageHeight / pageWidth * screenWidth);
						
						// -------- Preview Fast (half size) --------
						int renderWidth = screenWidth; 
						int renderHeight = calculatedHeight;
						
						Bitmap previewBitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888);
						Rect rect = new Rect(0, 0, renderWidth, renderHeight);
						page.render(previewBitmap, rect, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
						                    
						mainHandler.post(() -> {
							ImageView img = pageViews.get(index);
							if (img != null && img.getDrawable() == null) {
								img.setImageBitmap(previewBitmap);
							}
						});
						
						// -------- After stopping scrolling: Full render --------
						mainHandler.postDelayed(() -> executor.submit(() -> {
							try {
								Bitmap fullBitmap = Bitmap.createBitmap(screenWidth, calculatedHeight, Bitmap.Config.ARGB_8888);
								Rect fullRect = new Rect(0, 0, screenWidth, calculatedHeight);
								page.render(fullBitmap, fullRect, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
								
								pageCache.put(index, fullBitmap);
								
								mainHandler.post(() -> {
									ImageView img = pageViews.get(index);
									if (img != null) img.setImageBitmap(fullBitmap);
								});
							} catch (Exception e) {
								e.printStackTrace();
							} finally {
								page.close();
							}
						}), 200); // Wait 200ms after scrolling
						
					} catch (Exception e) {
						e.printStackTrace();
					}
				});
			}
		}
		
		// Remove remote pages from cache
		pageCache.snapshot().keySet().removeIf(i -> {
			if (i < start || i > end) {
				Bitmap bmp = pageCache.get(i);
				if (bmp != null && !bmp.isRecycled()) {
					pageCache.remove(i);
					mainHandler.post(() -> {
						ImageView img = pageViews.get(i);
						if (img != null) img.setImageDrawable(null);
					});
					bmp.recycle();
				}
				return true;
			}
			return false;
		});
	}
	
	@Deprecated
	public void showMessage(String _s) {
		Toast.makeText(getApplicationContext(), _s, Toast.LENGTH_SHORT).show();
	}
}

