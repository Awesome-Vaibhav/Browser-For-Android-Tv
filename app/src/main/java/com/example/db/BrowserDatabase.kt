package com.example.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [Bookmark::class, HistoryEntry::class, DownloadItem::class, ExtensionScript::class],
    version = 1,
    exportSchema = false
)
abstract class BrowserDatabase : RoomDatabase() {

    abstract fun browserDao(): BrowserDao

    companion object {
        @Volatile
        private var INSTANCE: BrowserDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): BrowserDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BrowserDatabase::class.java,
                    "browser_database"
                )
                    .addCallback(DatabaseCallback(scope))
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(
            private val scope: CoroutineScope
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    scope.launch(Dispatchers.IO) {
                        prepopulateExtensions(database.browserDao())
                    }
                }
            }

            private suspend fun prepopulateExtensions(dao: BrowserDao) {
                // Populate default extension content scripts
                dao.insertExtension(
                    ExtensionScript(
                        name = "AdBlock Lite",
                        description = "Lightweight integrated overlay ad banner and pop-up container block.",
                        category = "AdBlock",
                        scriptContent = """
                            (function() {
                                const adSelectors = [
                                    'div[id*="google_ads"]', 'div[class*="google_ads"]', 'iframe[src*="doubleclick"]',
                                    'div[class*="adsbygoogle"]', 'div[class*="banner-ads"]', 'div[id*="banner-ads"]',
                                    'amp-embed[type="adsense"]', 'div[class*="Sponsored"]', 'div[class*="commercial-ads"]',
                                    'aside[class*="advertisement"]', 'div[class*="advertisement"]', 'div[class*="ads-container"]',
                                    'iframe[id*="google_ads"]', 'div[id*="ad-slot"]', 'ins.adsbygoogle'
                                ];
                                function removeAds() {
                                    adSelectors.forEach(selector => {
                                        document.querySelectorAll(selector).forEach(el => {
                                            el.style.display = 'none';
                                            el.remove();
                                        });
                                    });
                                }
                                removeAds();
                                setInterval(removeAds, 2500);
                                console.log("AdBlock Lite Injected and Watching");
                            })();
                        """.trimIndent(),
                        isEnabled = true,
                        isUserAdded = false
                    )
                )

                dao.insertExtension(
                    ExtensionScript(
                        name = "Ambient Mode",
                        description = "Cozy night and dark overlay forced styling across any web destination.",
                        category = "Style",
                        scriptContent = """
                            (function() {
                                let style = document.getElementById('ambient-mode-styling');
                                if (!style) {
                                    style = document.createElement('style');
                                    style.id = 'ambient-mode-styling';
                                    style.innerHTML = `
                                        html, body {
                                            background-color: #151515 !important;
                                            color: #e0e0e0 !important;
                                        }
                                        div, p, span, li, a {
                                            background-color: transparent !important;
                                            color: #dfdfdf !important;
                                        }
                                        img, video, iframe {
                                            opacity: 0.82 !important;
                                        }
                                    `;
                                    document.head.appendChild(style);
                                    console.log("Ambient Mode Styled Injected");
                                }
                            })();
                        """.trimIndent(),
                        isEnabled = false,
                        isUserAdded = false
                    )
                )

                dao.insertExtension(
                    ExtensionScript(
                        name = "AutoScroll Helper",
                        description = "Injects an automated downward scroll loop. Double click the TV remote center to play.",
                        category = "Utility",
                        scriptContent = """
                            (function() {
                                if (window.autoScrollTimer) {
                                    clearInterval(window.autoScrollTimer);
                                    window.autoScrollTimer = null;
                                    console.log("AutoScroll Halted");
                                } else {
                                    window.autoScrollTimer = setInterval(function() {
                                        window.scrollBy({ top: 3, behavior: 'smooth' });
                                    }, 50);
                                    console.log("AutoScroll Running");
                                }
                            })();
                        """.trimIndent(),
                        isEnabled = false,
                        isUserAdded = false
                    )
                )
            }
        }
    }
}
