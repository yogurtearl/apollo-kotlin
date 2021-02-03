package com.apollographql.apollo

import com.apollographql.apollo.Utils.immediateExecutor
import com.apollographql.apollo.Utils.immediateExecutorService
import com.apollographql.apollo.Utils.mockResponse
import com.apollographql.apollo.api.Input
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.cache.CacheHeaders
import com.apollographql.apollo.cache.normalized.Record
import com.apollographql.apollo.cache.normalized.CacheKey
import com.apollographql.apollo.cache.normalized.NormalizedCache
import com.apollographql.apollo.cache.normalized.RecordFieldJsonAdapter
import com.apollographql.apollo.cache.normalized.MemoryCacheFactory
import com.apollographql.apollo.cache.normalized.NormalizedCacheFactory
import com.apollographql.apollo.fetcher.ApolloResponseFetchers.NETWORK_ONLY
import com.apollographql.apollo.integration.normalizer.EpisodeHeroNameQuery
import com.apollographql.apollo.integration.normalizer.EpisodeHeroNameWithIdQuery
import com.apollographql.apollo.integration.normalizer.HeroAndFriendsNamesWithIDsQuery
import com.apollographql.apollo.integration.normalizer.type.Episode.EMPIRE
import com.apollographql.apollo.integration.normalizer.type.Episode.NEWHOPE
import com.apollographql.apollo.rx2.Rx2Apollo
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import io.reactivex.disposables.Disposable
import io.reactivex.observers.TestObserver
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class Rx2ApolloTest {
  private lateinit var apolloClient: ApolloClient

  @get:Rule
  val server = MockWebServer()

  @Before
  fun setup() {
    val okHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher(immediateExecutorService()))
        .build()
    apolloClient = ApolloClient.builder()
        .serverUrl(server.url("/"))
        .dispatcher(immediateExecutor())
        .okHttpClient(okHttpClient)
        .normalizedCache(MemoryCacheFactory(maxSizeBytes = Int.MAX_VALUE), IdFieldCacheKeyResolver())
        .build()
  }

  @Test
  @Throws(Exception::class)
  fun callProducesValue() {
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))
    Rx2Apollo
        .from(apolloClient.query(EpisodeHeroNameQuery(Input.fromNullable(EMPIRE))))
        .test()
        .assertNoErrors()
        .assertComplete()
        .assertValue({ response ->
          assertThat(response.data?.hero?.name).isEqualTo("R2-D2")
          true
        })
  }

  @Test
  @Throws(Exception::class)
  fun callIsCanceledWhenDisposed() {
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))
    val testObserver: TestObserver<Response<EpisodeHeroNameQuery.Data>> = TestObserver<Response<EpisodeHeroNameQuery.Data>>()
    val disposable: Disposable = Rx2Apollo
        .from(apolloClient.query(EpisodeHeroNameQuery(Input.fromNullable(EMPIRE))))
        .subscribeWith(testObserver)
    disposable.dispose()
    testObserver.assertComplete()
    Truth.assertThat(testObserver.isDisposed).isTrue()
  }

  @Test
  @Throws(Exception::class)
  fun prefetchCompletes() {
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))
    Rx2Apollo
        .from(apolloClient.prefetch(EpisodeHeroNameQuery(Input.fromNullable(EMPIRE))))
        .test()
        .assertNoErrors()
        .assertComplete()
  }

  @Test
  @Throws(Exception::class)
  fun prefetchIsCanceledWhenDisposed() {
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))
    val testObserver: TestObserver<EpisodeHeroNameQuery.Data> = TestObserver<EpisodeHeroNameQuery.Data>()
    val disposable: Disposable = Rx2Apollo
        .from(apolloClient.prefetch(EpisodeHeroNameQuery(Input.fromNullable(EMPIRE))))
        .observeOn(TestScheduler())
        .subscribeWith(testObserver)
    disposable.dispose()
    testObserver.assertNotComplete()
    Truth.assertThat(testObserver.isDisposed).isTrue()
  }

  @Test
  @Throws(Exception::class)
  fun retryDoesNotThrow() {
    server.enqueue(MockResponse().setResponseCode(500))
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))
    val observer: TestObserver<EpisodeHeroNameQuery.Data> = TestObserver<EpisodeHeroNameQuery.Data>()
    Rx2Apollo
        .from(apolloClient.query(EpisodeHeroNameQuery(Input.fromNullable(EMPIRE))).watcher())
        .retry(1)
        .map { response -> response.data }
        .subscribeWith(observer)
    observer.assertValueCount(1)
        .assertValueAt(0) { data ->
          assertThat(data.hero?.name).isEqualTo("R2-D2")
          true
        }
  }

  @Test
  @Throws(Exception::class)
  fun queryWatcherUpdatedSameQueryDifferentResults() {
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))
    val observer = TestObserver<EpisodeHeroNameWithIdQuery.Data>()
    Rx2Apollo
        .from(apolloClient.query(EpisodeHeroNameWithIdQuery(Input.fromNullable(EMPIRE))).watcher())
        .map { response -> response.data }
        .subscribeWith(observer)

    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_CHANGE))
    apolloClient.query(EpisodeHeroNameWithIdQuery(Input.fromNullable(EMPIRE)))
        .responseFetcher(NETWORK_ONLY)
        .enqueue(null)
    observer.assertValueCount(2)
        .assertValueAt(0) { data ->
          assertThat(data.hero?.name).isEqualTo("R2-D2")
          true
        }
        .assertValueAt(1) { data ->
          assertThat(data.hero?.name).isEqualTo("Artoo")
          true
        }
  }

  @Test
  @Throws(Exception::class)
  fun queryWatcherNotUpdatedSameQuerySameResults() {
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))
    val observer: TestObserver<EpisodeHeroNameQuery.Data> = TestObserver<EpisodeHeroNameQuery.Data>()
    Rx2Apollo
        .from(apolloClient.query(EpisodeHeroNameQuery(Input.fromNullable(EMPIRE))).watcher())
        .map({ response -> response.data })
        .subscribeWith(observer)
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))
    apolloClient.query(EpisodeHeroNameQuery(Input.fromNullable(EMPIRE))).responseFetcher(NETWORK_ONLY)
        .enqueue(null)
    observer
        .assertValueCount(1)
        .assertValueAt(0) { data ->
          assertThat(data.hero?.name).isEqualTo("R2-D2")
          true
        }
  }

  @Test
  @Throws(Exception::class)
  fun queryWatcherUpdatedDifferentQueryDifferentResults() {
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))
    val observer = TestObserver<EpisodeHeroNameWithIdQuery.Data>()
    Rx2Apollo
        .from(apolloClient.query(EpisodeHeroNameWithIdQuery(Input.fromNullable(EMPIRE))).watcher())
        .map { response -> response.data }
        .subscribeWith(observer)
    server.enqueue(mockResponse("HeroAndFriendsNameWithIdsNameChange.json"))
    apolloClient.query(HeroAndFriendsNamesWithIDsQuery(Input.fromNullable(NEWHOPE))).enqueue(null)
    observer
        .assertValueCount(2)
        .assertValueAt(0) { data ->
          assertThat(data.hero?.name).isEqualTo("R2-D2")
          true
        }
        .assertValueAt(1) { data ->
          assertThat(data.hero?.name).isEqualTo("Artoo")
          true
        }
  }

  @Test
  @Throws(Exception::class)
  fun queryWatcherNotCalledWhenCanceled() {
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_WITH_ID))
    val testObserver: TestObserver<EpisodeHeroNameQuery.Data> = TestObserver<EpisodeHeroNameQuery.Data>()
    val scheduler = TestScheduler()
    val disposable: Disposable = Rx2Apollo
        .from(apolloClient.query(EpisodeHeroNameQuery(Input.fromNullable(EMPIRE))).watcher())
        .map { response -> response.data }
        .observeOn(scheduler)
        .subscribeWith(testObserver)

    scheduler.triggerActions()
    server.enqueue(mockResponse(FILE_EPISODE_HERO_NAME_CHANGE))

    apolloClient.query(EpisodeHeroNameQuery(Input.fromNullable(EMPIRE))).responseFetcher(NETWORK_ONLY)
        .enqueue(null)
    disposable.dispose()
    scheduler.triggerActions()
    testObserver
        .assertValueCount(1)
        .assertValueAt(0) { data ->
          assertThat(data.hero?.name).isEqualTo("R2-D2")
          true
        }
  }

  companion object {
    private const val FILE_EPISODE_HERO_NAME_WITH_ID = "EpisodeHeroNameResponseWithId.json"
    private const val FILE_EPISODE_HERO_NAME_CHANGE = "EpisodeHeroNameResponseNameChange.json"
  }
}
