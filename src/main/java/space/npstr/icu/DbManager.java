/*
 * Copyright (C) 2018 Dennis Neufeld
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package space.npstr.icu;

import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import net.ttddyy.dsproxy.listener.logging.SLF4JLogLevel;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.hibernate.cache.jcache.internal.JCacheRegionFactory;
import org.hibernate.cfg.Environment;
import org.hibernate.tool.schema.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.npstr.icu.info.AppInfo;
import space.npstr.sqlsauce.DatabaseConnection;
import space.npstr.sqlsauce.DatabaseWrapper;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.TimeUnit;

/**
 * Created by napster on 13.02.18.
 * <p>
 * Serves a lazily initialized database wrapper singletons
 */
@ThreadSafe
public class DbManager {

    private static final Logger log = LoggerFactory.getLogger(DbManager.class);

    @Nullable
    private DatabaseConnection connection;
    @Nullable
    private volatile DatabaseWrapper defaultDbWrapper;
    private final Object defaultDbWrapperInitLock = new Object();

    public DatabaseWrapper getDefaultDbWrapper() {
        DatabaseWrapper singleton = defaultDbWrapper;
        if (singleton == null) {
            synchronized (defaultDbWrapperInitLock) {
                singleton = defaultDbWrapper;
                if (singleton == null) {
                    connection = initDefaultDbConn();
                    defaultDbWrapper = singleton = new DatabaseWrapper(connection);
                }
            }
        }
        return singleton;
    }

    public void shutdown() {
        synchronized (defaultDbWrapperInitLock) {
            if (connection != null) {
                connection.shutdown();
            }
        }
    }

    private static DatabaseConnection initDefaultDbConn() {
        try {
            return new DatabaseConnection.Builder("postgres", Config.C.jdbcUrl)
                    .setDialect(org.hibernate.dialect.PostgreSQL95Dialect.class.getName())
                    .addEntityPackage("space.npstr.icu.db.entities")
                    .setAppName("icu_" + AppInfo.getAppInfo().getVersionBuild())
                    .setProxyDataSourceBuilder(new ProxyDataSourceBuilder()
                            .logSlowQueryBySlf4j(10, TimeUnit.SECONDS, SLF4JLogLevel.WARN, "SlowQueryLog")
                            .multiline()
                    )
                    .setHibernateProperty(Environment.HBM2DDL_AUTO, Action.UPDATE.name().toLowerCase())
                    .setHibernateProperty(Environment.USE_SECOND_LEVEL_CACHE, true)
                    .setHibernateProperty(Environment.USE_QUERY_CACHE, true)
                    .setHibernateProperty(Environment.CACHE_REGION_FACTORY, JCacheRegionFactory.class.getName())
                    .setHibernateProperty("hibernate.javax.cache.provider", CaffeineCachingProvider.class.getName())
                    .setHibernateProperty("hibernate.javax.cache.missing_cache_strategy", "fail")

                    //hide some exception spam on start, as postgres does not support CLOBs
                    // https://stackoverflow.com/questions/43905119/postgres-error-method-org-postgresql-jdbc-pgconnection-createclob-is-not-imple
                    .setHibernateProperty(Environment.NON_CONTEXTUAL_LOB_CREATION, true)

                    // these may improve performance on slow machines / big databases
                    // see https://stackoverflow.com/questions/10075081/hibernate-slow-to-acquire-postgres-connection
                    // and https://stackoverflow.com/questions/14445838/hibernate-startup-very-slow
                    .setHibernateProperty(Environment.USE_GET_GENERATED_KEYS, true)
                    .setHibernateProperty("hibernate.temp.use_jdbc_metadata_defaults", false)
                    .build();
        } catch (Exception e) {
            String message = "Failed to set up database";
            log.error(message, e);
            throw new RuntimeException(message, e);
        }
    }
}
