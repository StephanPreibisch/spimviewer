/*
 * #%L
 * BigDataViewer core classes with minimal dependencies
 * %%
 * Copyright (C) 2012 - 2015 BigDataViewer authors
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package bdv.img.cache;

import bdv.cache.VolatileCache;
import bdv.cache.VolatileCacheEntry;
import bdv.cache.VolatileCacheValue;
import bdv.cache.VolatileCacheValueLoader;
import bdv.cache.WeakSoftCache;
import bdv.cache.WeakSoftCacheImp;
import bdv.img.cache.CacheIoTiming.IoStatistics;
import bdv.img.cache.CacheIoTiming.IoTimeBudget;


/**
 * TODO rename
 * TODO revise javadoc
 *
 *
 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
 */
public class LoadingVolatileCache implements Cache
{
	private final int maxNumLevels;

	private final WeakSoftCache cache = WeakSoftCacheImp.getInstance();

	private final BlockingFetchQueues< Object > queue;

	private volatile long currentQueueFrame = 0;

	private final CacheIoTiming cacheIoTiming;

	private final FetcherThreads fetchers;

	private final Object cacheLock = new Object();

	/**
	 * @param maxNumLevels
	 *            the highest occurring TODO TODO mipmap TODO TODO level plus 1.
	 * @param numFetcherThreads
	 */
	public LoadingVolatileCache( final int maxNumLevels, final int numFetcherThreads )
	{
		this.maxNumLevels = maxNumLevels;

		cacheIoTiming = new CacheIoTiming();
		queue = new BlockingFetchQueues<>( maxNumLevels );
		fetchers = new FetcherThreads( cache, queue, numFetcherThreads );
	}

	/**
	 * Enqueue the {@link VolatileCacheEntry} if it hasn't been enqueued for this frame
	 * already.
	 */
	private void enqueueEntry( final VolatileCacheEntry< ?, ? > entry, final int priority, final boolean enqueuToFront )
	{
		if ( entry.getEnqueueFrame() < currentQueueFrame )
		{
			entry.setEnqueueFrame( currentQueueFrame );
			queue.put( entry.getKey(), priority, enqueuToFront );
		}
	}

	/**
	 * Load the data for the {@link VolatileCacheEntry} if it is not yet loaded (valid) and
	 * there is enough {@link IoTimeBudget} left. Otherwise, enqueue the
	 * {@link VolatileCacheEntry} if it hasn't been enqueued for this frame already.
	 */
	private void loadOrEnqueue( final VolatileCacheEntry< ?, ? > entry, final int priority, final boolean enqueuToFront )
	{
		final IoStatistics stats = cacheIoTiming.getThreadGroupIoStatistics();
		final IoTimeBudget budget = stats.getIoTimeBudget();
		final long timeLeft = budget.timeLeft( priority );
		if ( timeLeft > 0 )
		{
			synchronized ( entry )
			{
				if ( entry.getValue().isValid() )
					return;
				enqueueEntry( entry, priority, enqueuToFront );
				final long t0 = stats.getIoNanoTime();
				stats.start();
				try
				{
					entry.wait( timeLeft  / 1000000l, 1 );
				}
				catch ( final InterruptedException e )
				{}
				stats.stop();
				final long t = stats.getIoNanoTime() - t0;
				budget.use( t, priority );
			}
		}
		else
			enqueueEntry( entry, priority, enqueuToFront );
	}

	private void loadEntryWithCacheHints( final VolatileCacheEntry< ?, ? > entry, final CacheHints cacheHints )
	{
		switch ( cacheHints.getLoadingStrategy() )
		{
		case VOLATILE:
		default:
			enqueueEntry( entry, cacheHints.getQueuePriority(), cacheHints.isEnqueuToFront() );
			break;
		case BLOCKING:
			while ( true )
				try
				{
					entry.loadIfNotValid();
					break;
				}
				catch ( final InterruptedException e )
				{}
			break;
		case BUDGETED:
			if ( !entry.getValue().isValid() )
				loadOrEnqueue( entry, cacheHints.getQueuePriority(), cacheHints.isEnqueuToFront() );
			break;
		case DONTLOAD:
			break;
		}
	}

	/**
	 * Get a cell if it is in the cache or null. Note, that a cell being in the
	 * cache only means that there is a data array, but not necessarily that the
	 * data has already been loaded.
	 * <p>
	 * If the cell data has not been loaded, do the following, depending on the
	 * {@link LoadingStrategy}:
	 * <ul>
	 * <li>{@link LoadingStrategy#VOLATILE}: Enqueue the cell for asynchronous
	 * loading by a fetcher thread, if it has not been enqueued in the current
	 * frame already.
	 * <li>{@link LoadingStrategy#BLOCKING}: Load the cell data immediately.
	 * <li>{@link LoadingStrategy#BUDGETED}: Load the cell data immediately if
	 * there is enough {@link IoTimeBudget} left for the current thread group.
	 * Otherwise enqueue for asynchronous loading, if it has not been enqueued
	 * in the current frame already.
	 * <li>{@link LoadingStrategy#DONTLOAD}: Do nothing.
	 * </ul>
	 *
	 * @param key
	 *            coordinate of the cell (comprising timepoint, setup, level,
	 *            and flattened index).
	 * @param cacheHints
	 *            {@link LoadingStrategy}, queue priority, and queue order.
	 * @return a cell with the specified coordinates or null.
	 */
	public < K, V extends VolatileCacheValue > V getGlobalIfCached( final K key, final CacheHints cacheHints )
	{
		final VolatileCacheEntry< K, V > entry = cache.get( key );
		if ( entry != null )
		{
			loadEntryWithCacheHints( entry, cacheHints );
			return entry.getValue();
		}
		return null;
	}

	/**
	 * Create a new cell with the specified coordinates, if it isn't in the
	 * cache already. Depending on the {@link LoadingStrategy}, do the
	 * following:
	 * <ul>
	 * <li>{@link LoadingStrategy#VOLATILE}: Enqueue the cell for asynchronous
	 * loading by a fetcher thread.
	 * <li>{@link LoadingStrategy#BLOCKING}: Load the cell data immediately.
	 * <li>{@link LoadingStrategy#BUDGETED}: Load the cell data immediately if
	 * there is enough {@link IoTimeBudget} left for the current thread group.
	 * Otherwise enqueue for asynchronous loading.
	 * <li>{@link LoadingStrategy#DONTLOAD}: Do nothing.
	 * </ul>
	 *
	 * @param key
	 *            coordinate of the cell (comprising timepoint, setup, level,
	 *            and flattened index).
	 * @param cacheHints
	 *            {@link LoadingStrategy}, queue priority, and queue order.
	 * @return a cell with the specified coordinates.
	 */
	public < K, V extends VolatileCacheValue > V createGlobal( final K key, final CacheHints cacheHints, final VolatileCacheValueLoader< K, V > cacheLoader )
	{
		Entry< K, V > entry = null;

		synchronized ( cacheLock )
		{
			entry = cache.get( key );
			if ( entry == null )
			{
				final V value = cacheLoader.createEmptyValue( key );
				entry = new Entry<>( key, value, cacheLoader );
				cache.putWeak( key, entry );
			}
		}

		loadEntryWithCacheHints( entry, cacheHints );
		return entry.getValue();
	}

	/**
	 * Prepare the cache for providing data for the "next frame":
	 * <ul>
	 * <li>the contents of fetch queues is moved to the prefetch.
	 * <li>some cleaning up of garbage collected entries ({@link VolatileCache#finalizeRemovedCacheEntries()}).
	 * <li>the internal frame counter is incremented, which will enable
	 * previously enqueued requests to be enqueued again for the new frame.
	 * </ul>
	 */
	@Override
	public void prepareNextFrame()
	{
		queue.clear();
		cache.finalizeRemovedCacheEntries();
		++currentQueueFrame;
	}

	/**
	 * (Re-)initialize the IO time budget, that is, the time that can be spent
	 * in blocking IO per frame/
	 *
	 * @param partialBudget
	 *            Initial budget (in nanoseconds) for priority levels 0 through
	 *            <em>n</em>. The budget for level <em>i&gt;j</em> must always be
	 *            smaller-equal the budget for level <em>j</em>. If <em>n</em>
	 *            is smaller than the maximum number of mipmap levels, the
	 *            remaining priority levels are filled up with budget[n].
	 */
	@Override
	public void initIoTimeBudget( final long[] partialBudget )
	{
		final IoStatistics stats = cacheIoTiming.getThreadGroupIoStatistics();
		if ( stats.getIoTimeBudget() == null )
			stats.setIoTimeBudget( new IoTimeBudget( maxNumLevels ) );
		stats.getIoTimeBudget().reset( partialBudget );
	}

	/**
	 * Get the {@link CacheIoTiming} that provides per thread-group IO
	 * statistics and budget.
	 */
	@Override
	public CacheIoTiming getCacheIoTiming()
	{
		return cacheIoTiming;
	}

	/**
	 * Remove all references to loaded data as well as all enqueued requests
	 * from the cache.
	 */
	public void clearCache()
	{
		// TODO: we should only clear out our own cache entries not the entire global cache!
		cache.clearCache();
		prepareNextFrame();
		// TODO: add a full clear to BlockingFetchQueues.
		// (BlockingFetchQueues.clear() moves stuff to the prefetchQueue.)
	}

	public FetcherThreads getFetcherThreads()
	{
		return fetchers;
	}

	class Entry< K, V extends VolatileCacheValue > implements VolatileCacheEntry< K, V >
	{
		private final K key;

		private V value;

		private final VolatileCacheValueLoader< K, V > loader;

		/**
		 * When was this entry last enqueued for loading (see
		 * {@link VolatileGlobalCellCache#currentQueueFrame}). This is initialized
		 * to -1. When the entry's data becomes valid, it is set to
		 * {@link Long#MAX_VALUE}.
		 */
		private long enqueueFrame;

		public Entry( final K key, final V data, final VolatileCacheValueLoader< K, V > loader )
		{
			this.key = key;
			this.value = data;
			this.loader = loader;
			enqueueFrame = -1;
		}

		@Override
		public void loadIfNotValid() throws InterruptedException
		{
			/*
			 * TODO: the assumption for following synchronisation pattern is
			 * that isValid() will never go from true to false. When
			 * invalidation API is added, that might change.
			 */
			if ( !value.isValid() )
			{
				synchronized ( this )
				{
					if ( !value.isValid() )
					{
						value = loader.load( key );
						enqueueFrame = Long.MAX_VALUE;
						cache.putSoft( key, this );
						notifyAll();
					}
				}
			}
		}

		@Override
		public K getKey()
		{
			return key;
		}

		@Override
		public V getValue()
		{
			return value;
		}

		@Override
		public long getEnqueueFrame()
		{
			return enqueueFrame;
		}

		@Override
		public void setEnqueueFrame( final long f )
		{
			enqueueFrame = f;
		}
	}
}