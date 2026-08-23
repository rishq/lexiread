package com.lexiread

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lexiread.presentation.details.BookDetailsScreen
import com.lexiread.presentation.details.BookDetailsViewModel
import com.lexiread.presentation.di.AppContainer
import com.lexiread.presentation.home.HomeScreen
import com.lexiread.presentation.home.HomeViewModel
import com.lexiread.presentation.library.LibraryScreen
import com.lexiread.presentation.library.LibraryViewModel
import com.lexiread.presentation.navigation.Screen
import com.lexiread.presentation.navigation.bottomNavItems
import com.lexiread.presentation.reader.ReaderScreen
import com.lexiread.presentation.reader.ReaderViewModel
import com.lexiread.presentation.search.SearchScreen
import com.lexiread.presentation.search.SearchViewModel
import com.lexiread.presentation.settings.SettingsScreen
import com.lexiread.presentation.settings.SettingsViewModel
import com.lexiread.presentation.vocabulary.VocabularyScreen
import com.lexiread.presentation.vocabulary.VocabularyViewModel
import com.lexiread.ui.theme.LexiReadTheme

class MainActivity : ComponentActivity() {

  private lateinit var container: AppContainer
  var onVolumeKeyEvent: ((Int) -> Boolean)? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    container = (application as LexiReadApp).container

    setContent {
      val readerSettings by container.userPreferencesManager.readerSettings.collectAsStateWithLifecycle(initialValue = com.lexiread.domain.model.ReaderSettings())

      LexiReadTheme(readerTheme = readerSettings.theme) {
        LexiReadApp(container = container, onSetVolumeKeyListener = { listener ->
            onVolumeKeyEvent = listener
        })
      }
    }
  }

  override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
    if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
      if (onVolumeKeyEvent?.invoke(keyCode) == true) {
        return true
      }
    }
    return super.onKeyDown(keyCode, event)
  }
}

@Composable
fun LexiReadApp(container: AppContainer, onSetVolumeKeyListener: (((Int) -> Boolean)?) -> Unit) {
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = navBackStackEntry?.destination?.route

  val showBottomBar = bottomNavItems.any { it.route == currentRoute }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    bottomBar = {
      if (showBottomBar) {
        NavigationBar {
          bottomNavItems.forEach { item ->
            val selected = currentRoute == item.route
            NavigationBarItem(
              selected = selected,
              onClick = {
                navController.navigate(item.route) {
                  popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                  }
                  launchSingleTop = true
                  restoreState = true
                }
              },
              icon = {
                Icon(
                  imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                  contentDescription = item.title
                )
              },
              label = {
                Text(
                  text = item.title,
                  maxLines = 1,
                  overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                  style = MaterialTheme.typography.labelSmall
                )
              }
            )
          }
        }
      }
    }
  ) { innerPadding ->
    NavHost(
      navController = navController,
      startDestination = Screen.Home.route,
      modifier = Modifier.padding(innerPadding)
    ) {
      composable(Screen.Home.route) {
        val homeViewModel: HomeViewModel = viewModel(
          factory = HomeViewModel.Factory(container.bookRepository, container.vocabularyRepository)
        )
        HomeScreen(
          viewModel = homeViewModel,
          onBookClick = { bookId -> navController.navigate(Screen.BookDetails.createRoute(bookId)) },
          onReadClick = { bookId -> navController.navigate(Screen.Reader.createRoute(bookId)) },
          onSearchClick = { navController.navigate(Screen.Search.route) }
        )
      }

      composable(Screen.Library.route) {
        val libraryViewModel: LibraryViewModel = viewModel(
          factory = LibraryViewModel.Factory(container.bookRepository, container.bookImporter)
        )
        LibraryScreen(
          viewModel = libraryViewModel,
          onBookClick = { bookId -> navController.navigate(Screen.BookDetails.createRoute(bookId)) },
          onReadClick = { bookId -> navController.navigate(Screen.Reader.createRoute(bookId)) }
        )
      }

      composable(Screen.Search.route) {
        val searchViewModel: SearchViewModel = viewModel(
          factory = SearchViewModel.Factory(container.bookRepository)
        )
        SearchScreen(
          viewModel = searchViewModel,
          onBookClick = { bookId -> navController.navigate(Screen.BookDetails.createRoute(bookId)) },
          onReadClick = { bookId -> navController.navigate(Screen.Reader.createRoute(bookId)) }
        )
      }

      composable(Screen.Vocabulary.route) {
        val vocabularyViewModel: VocabularyViewModel = viewModel(
          factory = VocabularyViewModel.Factory(container.vocabularyRepository, container.ttsHelper)
        )
        VocabularyScreen(viewModel = vocabularyViewModel)
      }

      composable(Screen.Settings.route) {
        val settingsViewModel: SettingsViewModel = viewModel(
          factory = SettingsViewModel.Factory(container.userPreferencesManager)
        )
        SettingsScreen(viewModel = settingsViewModel)
      }

      composable(
        route = Screen.BookDetails.route,
        arguments = listOf(navArgument("bookId") { type = NavType.StringType })
      ) { backStackEntry ->
        val bookId = backStackEntry.arguments?.getString("bookId") ?: ""
        val detailsViewModel: BookDetailsViewModel = viewModel(
          factory = BookDetailsViewModel.Factory(bookId, container.bookRepository)
        )
        BookDetailsScreen(
          viewModel = detailsViewModel,
          onBackClick = { navController.popBackStack() },
          onReadClick = { id -> navController.navigate(Screen.Reader.createRoute(id)) }
        )
      }

      composable(
        route = Screen.Reader.route,
        arguments = listOf(navArgument("bookId") { type = NavType.StringType })
      ) { backStackEntry ->
        val bookId = backStackEntry.arguments?.getString("bookId") ?: ""
        val readerViewModel: ReaderViewModel = viewModel(
          factory = ReaderViewModel.Factory(
            bookId = bookId,
            bookRepository = container.bookRepository,
            dictionaryRepository = container.dictionaryRepository,
            translationRepository = container.translationRepository,
            vocabularyRepository = container.vocabularyRepository,
            aiRepository = container.aiRepository,
            preferencesManager = container.userPreferencesManager,
            ttsHelper = container.ttsHelper,
            bookImporter = container.bookImporter,
            paginationEngine = container.paginationEngine
          )
        )

        androidx.compose.runtime.DisposableEffect(readerViewModel) {
          onSetVolumeKeyListener { keyCode -> readerViewModel.onVolumeKeyEvent(keyCode) }
          onDispose { onSetVolumeKeyListener(null) }
        }

        ReaderScreen(
          viewModel = readerViewModel,
          onBackClick = { navController.popBackStack() }
        )
      }
    }
  }
}

