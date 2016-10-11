package bdv.cache.util;

import java.util.ArrayList;
import java.util.function.IntFunction;

import bdv.cache.LoadingVolatileCache;
import bdv.cache.VolatileCacheValue;

/**
 * A set of threads that load data. Each thread does the following in a loop:
 * <ol>
 * <li>Take the next {@code key} from a queue.</li>
 * <li>Try {@link Loader#load() loading} the key's data (retry until that
 * succeeds).</li>
 * </ol>
 * {@link FetcherThreads} are employed by {@link LoadingVolatileCache} to
 * asynchronously load {@link VolatileCacheValue}s.
 *
 * <p>
 * TODO Add shutdown() method.
 *
 * <p>
 * TODO This uses {@code WeakSoftCache<?,? extends VolatileCacheEntry>} only for
 * {@code get()}, could be replaced with something less restrictive?
 *
 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
 */
public class FetcherThreads< K >
{
	/**
	 * Loads data associated with a key.
	 *
	 * @param <K>
	 *            the key type.
	 */
	public interface Loader< K >
	{
		/**
		 * If this key's data is not yet valid, then load it. After the method
		 * returns, the data is guaranteed to be valid.
		 * <p>
		 * This must be implemented in a thread-safe manner. Multiple threads
		 * are allowed to call this method at the same time with the same key.
		 * The expected behaviour is that the data is loaded only once and the
		 * result is made visible on all threads.
		 *
		 * @throws InterruptedException
		 *             if the loading operation was interrupted.
		 */
		public void load( K key ) throws InterruptedException;
	}

	private final ArrayList< Fetcher< K > > fetchers;

	public FetcherThreads(
			final BlockingFetchQueues< K > queue,
			final Loader< K > loader,
			final int numFetcherThreads )
	{
		this( queue, loader, numFetcherThreads, i -> String.format( "Fetcher-%d", i ) );
	}

	/**
	 *
	 * @param cache the cache that contains entries to load.
	 * @param queue the queue from which request keys are taken.
	 * @param numFetcherThreads how many parallel fetcher threads to start.
	 * @param threadIndexToName a function for naming fetcher threads (takes an index and returns a name).
	 */
	public FetcherThreads(
			final BlockingFetchQueues< K > queue,
			final Loader< K > loader,
			final int numFetcherThreads,
			final IntFunction< String > threadIndexToName )
	{
		fetchers = new ArrayList<>( numFetcherThreads );
		for ( int i = 0; i < numFetcherThreads; ++i )
		{
			final Fetcher< K > f = new Fetcher<>( queue, loader );
			f.setDaemon( true );
			f.setName( threadIndexToName.apply( i ) );
			fetchers.add( f );
			f.start();
		}
	}

	/**
	 * Pause all Fetcher threads for the specified number of milliseconds.
	 */
	public void pauseFetcherThreadsFor( final long ms )
	{
		pauseFetcherThreadsUntil( System.currentTimeMillis() + ms );
	}

	/**
	 * pause all Fetcher threads until the given time (see
	 * {@link System#currentTimeMillis()}).
	 */
	public void pauseFetcherThreadsUntil( final long timeMillis )
	{
		for ( final Fetcher< K > f : fetchers )
			f.pauseUntil( timeMillis );
	}

	/**
	 * Wake up all Fetcher threads immediately. This ends any
	 * {@link #pauseFetcherThreadsFor(long)} and
	 * {@link #pauseFetcherThreadsUntil(long)} set earlier.
	 */
	public void wakeFetcherThreads()
	{
		for ( final Fetcher< K > f : fetchers )
			f.wakeUp();
	}

	static final class Fetcher< K > extends Thread
	{
		private final BlockingFetchQueues< K > queue;

		private final Loader< K > loader;

		private final Object lock = new Object();

		private volatile long pauseUntilTimeMillis = 0;

		public Fetcher(
				final BlockingFetchQueues< K > queue,
				final Loader< K > loader )
		{
			this.queue = queue;
			this.loader = loader;
		}

		@Override
		public final void run()
		{
			K key = null;
			while ( true )
			{
				while ( key == null )
					try
					{
						key = queue.take();
					}
					catch ( final InterruptedException e )
					{}
				long waitMillis = pauseUntilTimeMillis - System.currentTimeMillis();
				while ( waitMillis > 0 )
				{
					try
					{
						synchronized ( lock )
						{
							lock.wait( waitMillis );
						}
					}
					catch ( final InterruptedException e )
					{}
					waitMillis = pauseUntilTimeMillis - System.currentTimeMillis();
				}
				try
				{
					loader.load( key );
					key = null;
				}
				catch ( final InterruptedException e )
				{}
			}
		}

		public void pauseUntil( final long timeMillis )
		{
			pauseUntilTimeMillis = timeMillis;
			interrupt();
		}

		public void wakeUp()
		{
			pauseUntilTimeMillis = 0;
			synchronized ( lock )
			{
				lock.notify();
			}
		}
	}
}
