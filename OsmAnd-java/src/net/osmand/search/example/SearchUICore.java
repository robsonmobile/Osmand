package net.osmand.search.example;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;

import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.StringMatcher;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.data.LatLon;
import net.osmand.search.example.core.ObjectType;
import net.osmand.search.example.core.SearchCoreAPI;
import net.osmand.search.example.core.SearchCoreFactory;
import net.osmand.search.example.core.SearchPhrase;
import net.osmand.search.example.core.SearchResult;
import net.osmand.search.example.core.SearchSettings;
import net.osmand.search.example.core.SearchWord;
import net.osmand.util.Algorithms;

public class SearchUICore {

	private static final Log LOG = PlatformUtil.getLog(SearchUICore.class); 
	private SearchPhrase phrase;
	private List<SearchResult> currentSearchResults = new ArrayList<>();
	
	private ThreadPoolExecutor singleThreadedExecutor;
	private LinkedBlockingQueue<Runnable> taskQueue;
	private Runnable onResultsComplete = null;
	private AtomicInteger requestNumber = new AtomicInteger();
	private int totalLimit = 20; // -1 unlimited
	
	List<SearchCoreAPI> apis = new ArrayList<>();
	private SearchSettings searchSettings;
	
	
	public SearchUICore(BinaryMapIndexReader[] searchIndexes) {
		List<BinaryMapIndexReader> searchIndexesList = Arrays.asList(searchIndexes);
		taskQueue = new LinkedBlockingQueue<Runnable>();
		searchSettings = new SearchSettings(searchIndexesList);
		phrase = new SearchPhrase(searchSettings);
		singleThreadedExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, taskQueue);
		init();
	}
	
	public int getTotalLimit() {
		return totalLimit;
	}
	
	public void setTotalLimit(int totalLimit) {
		this.totalLimit = totalLimit;
	}
	
	public void init() {
//		apis.add(new SearchAmenityByNameAPI());
//		apis.add(new SearchAmenityByTypeAPI());
//		apis.add(new SearchAddressByNameAPI());
//		apis.add(new SearchStreetByCityAPI());
//		apis.add(new SearchBuildingAndIntersectionsByStreetAPI());
		apis.add(new SearchCoreFactory.SearchRegionByNameAPI());
		apis.add(new SearchCoreFactory.SearchAddressByNameAPI());
	}
	
	public List<SearchResult> getCurrentSearchResults() {
		return currentSearchResults;
	}
	
	public SearchPhrase getPhrase() {
		return phrase;
	}
	
	public void setOnResultsComplete(Runnable onResultsComplete) {
		this.onResultsComplete = onResultsComplete;
	}
	
	
	public void setSearchLocation(LatLon l) {
		searchSettings = searchSettings.setOriginalLocation(l);
	}
	

	private List<SearchResult> filterCurrentResults(SearchPhrase phrase) {
		List<SearchResult> rr = new ArrayList<>();
		List<SearchResult> l = currentSearchResults;
		for(SearchResult r : l) {
			if(filterOneResult(r, phrase)) {
				rr.add(r);
			}
		}
		return rr;
	}
	
	private boolean filterOneResult(SearchResult object, SearchPhrase phrase) {
		StringMatcher nameStringMatcher = phrase.getNameStringMatcher();
		
		return nameStringMatcher.matches(object.mainName);
	}

	public boolean selectSearchResult(SearchResult r) {
		this.phrase = this.phrase.selectWord(r);
		return true;
	}
	
	public List<SearchResult> search(final String text, final ResultMatcher<SearchResult> matcher) {
		List<SearchResult> list = new ArrayList<>();
		final int request = requestNumber.incrementAndGet();
		final SearchPhrase phrase = this.phrase.generateNewPhrase(text, searchSettings);
		this.phrase = phrase;
		list.addAll(filterCurrentResults(phrase));
		System.out.println("> Search phrase " + phrase + " " + list.size());
		singleThreadedExecutor.submit(new Runnable() {

			@Override
			public void run() {
				try {
					SearchResultMatcher rm = new SearchResultMatcher(matcher, request);
					if(rm.isCancelled()) {
						return;
					}
					Thread.sleep(200); // FIXME
					searchInBackground(phrase, rm);
					if (!rm.isCancelled()) {
						sortSearchResults(phrase, rm.getRequestResults());
						System.out.println(">> Search phrase " + phrase + " " + rm.getRequestResults().size());
						currentSearchResults = rm.getRequestResults();
						if (onResultsComplete != null) {
							onResultsComplete.run();
						}
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

			}

			
		});
		return list;
	}

	private void searchInBackground(final SearchPhrase phrase, SearchResultMatcher matcher) {
		for (SearchWord sw : phrase.getWords()) {
			if (sw.getType() == ObjectType.REGION) {
				phrase.selectFile((BinaryMapIndexReader) sw.getResult().object);
			}
		}
		ArrayList<SearchCoreAPI> lst = new ArrayList<>(apis);
		Collections.sort(lst, new Comparator<SearchCoreAPI>() {

			@Override
			public int compare(SearchCoreAPI o1, SearchCoreAPI o2) {
				return Algorithms.compare(o1.getSearchPriority(phrase),
						o2.getSearchPriority(phrase));
			}
		});
		for(SearchCoreAPI api : lst) {
			if(matcher.isCancelled()) {
				break;
			}
			try {
				api.search(phrase, matcher);
			} catch (IOException e) {
				LOG.error(e.getMessage(), e);
				e.printStackTrace();
			}
		}
	}
	
	
	

	
	private void sortSearchResults(SearchPhrase sp, List<SearchResult> searchResults) {
		// sort SearchResult by 1. searchDistance 2. Name
		final LatLon loc = sp.getLastTokenLocation();
		final Collator clt = Collator.getInstance();
		Collections.sort(searchResults, new Comparator<SearchResult>() {

			@Override
			public int compare(SearchResult o1, SearchResult o2) {
				double s1 = o1.getSearchDistance(loc);
				double s2 = o2.getSearchDistance(loc);
				int cmp = Double.compare(s1, s2);
				if(cmp != 0) {
					return cmp;
				}
				return clt.compare(o1.mainName, o2.mainName);
			}
		});
	}
	
	public class SearchResultMatcher implements  ResultMatcher<SearchResult>{
		private final List<SearchResult> requestResults = new ArrayList<>();
		private ResultMatcher<SearchResult> matcher;
		private final int request;
		int count = 0;
		
		
		public SearchResultMatcher(ResultMatcher<SearchResult> matcher, int request) {
			this.matcher = matcher;
			this.request = request;
		}
		
		public List<SearchResult> getRequestResults() {
			return requestResults;
		}
		@Override
		public boolean publish(SearchResult object) {
			if(matcher == null || matcher.publish(object)) {
				count++;
				if(totalLimit == -1 || count < totalLimit) {
					requestResults.add(object);	
				}
				return true;
			}
			return false;
		}
		@Override
		public boolean isCancelled() {
			boolean cancelled = request != requestNumber.get();
			return cancelled || (matcher != null && matcher.isCancelled());
		}		
	}	
}
