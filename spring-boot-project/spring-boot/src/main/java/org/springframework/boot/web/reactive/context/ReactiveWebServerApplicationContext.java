/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.web.reactive.context;

import java.util.function.Supplier;

import reactor.core.publisher.Mono;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.web.context.ConfigurableWebServerApplicationContext;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContextException;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.StringUtils;

/**
 * A {@link GenericReactiveWebApplicationContext} that can be used to bootstrap itself
 * from a contained {@link ReactiveWebServerFactory} bean.
 *
 * @author Brian Clozel
 * @since 2.0.0
 */
public class ReactiveWebServerApplicationContext extends GenericReactiveWebApplicationContext
		implements ConfigurableWebServerApplicationContext {

	/**
	 * ServerManager 对象
	 */
	private volatile ServerManager serverManager;

	/**
	 * 通过 {@link #setServerNamespace(String)} 注入。
	 *
	 * 不过貌似，一直没被注入过，可以暂时先无视
	 */
	private String serverNamespace;

	/**
	 * Create a new {@link ReactiveWebServerApplicationContext}.
	 */
	public ReactiveWebServerApplicationContext() {
	}

	/**
	 * Create a new {@link ReactiveWebServerApplicationContext} with the given
	 * {@code DefaultListableBeanFactory}.
	 * @param beanFactory the DefaultListableBeanFactory instance to use for this context
	 */
	public ReactiveWebServerApplicationContext(DefaultListableBeanFactory beanFactory) {
		super(beanFactory);
	}

	@Override
	public final void refresh() throws BeansException, IllegalStateException {
		try {
			// 调用父方法
			super.refresh();
		}
		catch (RuntimeException ex) {
			// <X> 停止 Reactive WebServer
			stopAndReleaseReactiveWebServer();
			throw ex;
		}
	}

	@Override
	protected void onRefresh() {
		// <1> 调用父方法
		super.onRefresh();
		try {
			// <2> 创建 WebServer
			createWebServer();
		}
		catch (Throwable ex) {
			throw new ApplicationContextException("Unable to start reactive web server", ex);
		}
	}

	private void createWebServer() {
		// 获得 ServerManager 对象。
		ServerManager serverManager = this.serverManager;
		// 如果不存在，则进行初始化
		if (serverManager == null) {
			String webServerFactoryBeanName = getWebServerFactoryBeanName();
			ReactiveWebServerFactory webServerFactory = getWebServerFactory(webServerFactoryBeanName);
			boolean lazyInit = getBeanFactory().getBeanDefinition(webServerFactoryBeanName).isLazyInit();
			this.serverManager = ServerManager.get(webServerFactory, lazyInit); // <1>
		}
		// <2> 初始化 PropertySource
		initPropertySources();
	}

	protected String getWebServerFactoryBeanName() {
		// Use bean names so that we don't consider the hierarchy
		// 获得 ServletWebServerFactory 类型对应的 Bean 的名字们
		String[] beanNames = getBeanFactory().getBeanNamesForType(ReactiveWebServerFactory.class);
		// 如果是 0 个，抛出 ApplicationContextException 异常，因为至少要一个
		if (beanNames.length == 0) {
			throw new ApplicationContextException(
					"Unable to start ReactiveWebApplicationContext due to missing " + "ReactiveWebServerFactory bean.");
		}
		// 如果是 > 1 个，抛出 ApplicationContextException 异常，因为不知道初始化哪个
		if (beanNames.length > 1) {
			throw new ApplicationContextException("Unable to start ReactiveWebApplicationContext due to multiple "
					+ "ReactiveWebServerFactory beans : " + StringUtils.arrayToCommaDelimitedString(beanNames));
		}
		// 获得 ReactiveWebServerFactory 类型对应的 Bean 对象
		return beanNames[0];
	}

	protected ReactiveWebServerFactory getWebServerFactory(String factoryBeanName) {
		return getBeanFactory().getBean(factoryBeanName, ReactiveWebServerFactory.class);
	}

	/**
	 * Return the {@link ReactiveWebServerFactory} that should be used to create the
	 * reactive web server. By default this method searches for a suitable bean in the
	 * context itself.
	 * @return a {@link ReactiveWebServerFactory} (never {@code null})
	 * @deprecated since 2.2.0 in favor of {@link #getWebServerFactoryBeanName()} and
	 * {@link #getWebServerFactory(String)}
	 */
	@Deprecated
	protected ReactiveWebServerFactory getWebServerFactory() {
		return getWebServerFactory(getWebServerFactoryBeanName());
	}

	@Override
	protected void finishRefresh() {
		// <1> 调用父方法
		super.finishRefresh();
		// <2> 启动 WebServer
		WebServer webServer = startReactiveWebServer();
		// <3> 如果创建 WebServer 成功，发布 ReactiveWebServerInitializedEvent 事件
		if (webServer != null) {
			publishEvent(new ReactiveWebServerInitializedEvent(webServer, this));
		}
	}

	private WebServer startReactiveWebServer() {
		ServerManager serverManager = this.serverManager;
		// <1> 获得 HttpHandler
		// <2> 启动 WebServer
		ServerManager.start(serverManager, this::getHttpHandler);
		// <3> 获得 WebServer
		return ServerManager.getWebServer(serverManager);
	}

	/**
	 * Return the {@link HttpHandler} that should be used to process the reactive web
	 * server. By default this method searches for a suitable bean in the context itself.
	 * @return a {@link HttpHandler} (never {@code null}
	 */
	protected HttpHandler getHttpHandler() {
		// Use bean names so that we don't consider the hierarchy
		// 获得 HttpHandler 类型对应的 Bean 的名字们
		String[] beanNames = getBeanFactory().getBeanNamesForType(HttpHandler.class);
		// 如果是 0 个，抛出 ApplicationContextException 异常，因为至少要一个
		if (beanNames.length == 0) {
			throw new ApplicationContextException(
					"Unable to start ReactiveWebApplicationContext due to missing HttpHandler bean.");
		}
		// 如果是 > 1 个，抛出 ApplicationContextException 异常，因为不知道初始化哪个
		if (beanNames.length > 1) {
			throw new ApplicationContextException(
					"Unable to start ReactiveWebApplicationContext due to multiple HttpHandler beans : "
							+ StringUtils.arrayToCommaDelimitedString(beanNames));
		}
		// 获得 HttpHandler 类型对应的 Bean 对象
		return getBeanFactory().getBean(beanNames[0], HttpHandler.class);
	}

	@Override
	protected void onClose() {
		// 调用父类方法
		super.onClose();
		// 关闭 WebServer
		stopAndReleaseReactiveWebServer();
	}

	private void stopAndReleaseReactiveWebServer() {
		ServerManager serverManager = this.serverManager;
		try {
			ServerManager.stop(serverManager); // <Y>
		}
		finally {
			this.serverManager = null;
		}
	}

	/**
	 * Returns the {@link WebServer} that was created by the context or {@code null} if
	 * the server has not yet been created.
	 * @return the web server
	 */
	@Override
	public WebServer getWebServer() {
		return ServerManager.getWebServer(this.serverManager);
	}

	@Override
	public String getServerNamespace() {
		return this.serverNamespace;
	}

	@Override
	public void setServerNamespace(String serverNamespace) {
		this.serverNamespace = serverNamespace;
	}

	/**
	 * {@link HttpHandler} that initializes its delegate on first request.
	 */
	private static final class LazyHttpHandler implements HttpHandler {

		private final Mono<HttpHandler> delegate;

		private LazyHttpHandler(Mono<HttpHandler> delegate) {
			this.delegate = delegate;
		}

		@Override
		public Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response) {
			return this.delegate.flatMap((handler) -> handler.handle(request, response));
		}

	}

	/**
	 * Internal class used to manage the server and the {@link HttpHandler}, taking care
	 * not to initialize the handler too early.
	 */
	static final class ServerManager implements HttpHandler {

		/**
		 * WebServer 对象
		 */
		private final WebServer server;

		private final boolean lazyInit;

		/**
		 * HttpHandler 对象，具体在 {@link #handle(ServerHttpRequest, ServerHttpResponse)} 方法中使用。
		 */
		private volatile HttpHandler handler;

		private ServerManager(ReactiveWebServerFactory factory, boolean lazyInit) {
			this.handler = this::handleUninitialized;// <1> 同下面
//          this.handler = new HttpHandler() {
//                @Override
//                public Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response) {
//                    return handleUninitialized(request, response);
//                }
//            };
			this.server = factory.getWebServer(this); // <2>
			this.lazyInit = lazyInit;
		}

		private Mono<Void> handleUninitialized(ServerHttpRequest request, ServerHttpResponse response) {
			throw new IllegalStateException("The HttpHandler has not yet been initialized");
		}

		@Override
		public Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response) {
			return this.handler.handle(request, response);
		}

		public HttpHandler getHandler() {
			return this.handler;
		}

		public static ServerManager get(ReactiveWebServerFactory factory, boolean lazyInit) {
			return new ServerManager(factory, lazyInit);
		}

		public static WebServer getWebServer(ServerManager manager) {
			return (manager != null) ? manager.server : null;
		}

		public static void start(ServerManager manager, Supplier<HttpHandler> handlerSupplier) {
			if (manager != null && manager.server != null) {
				// <1> 赋值 handler
				manager.handler = manager.lazyInit ? new LazyHttpHandler(Mono.fromSupplier(handlerSupplier))
						: handlerSupplier.get();
				// <2> 启动 server
				manager.server.start();
			}
		}

		public static void stop(ServerManager manager) {
			if (manager != null && manager.server != null) {
				try {
					manager.server.stop();
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}
		}

	}

}
