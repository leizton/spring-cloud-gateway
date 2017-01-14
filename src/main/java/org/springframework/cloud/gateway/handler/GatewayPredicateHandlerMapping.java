package org.springframework.cloud.gateway.handler;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.beans.BeansException;
import org.springframework.cloud.gateway.api.RouteReader;
import org.springframework.cloud.gateway.config.Route;
import org.springframework.cloud.gateway.config.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateFactory;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebHandler;

import reactor.core.publisher.Mono;

import static org.springframework.cloud.gateway.filter.GatewayFilter.GATEWAY_HANDLER_MAPPER_ATTR;
import static org.springframework.cloud.gateway.filter.GatewayFilter.GATEWAY_ROUTE_ATTR;

/**
 * @author Spencer Gibb
 */
public class GatewayPredicateHandlerMapping extends AbstractHandlerMapping {

	private Map<String, PredicateFactory> predicates = new LinkedHashMap<>();
	private RouteReader routeReader;
	private WebHandler webHandler;

	private List<Route> routes;

	public GatewayPredicateHandlerMapping(WebHandler webHandler, Map<String, PredicateFactory> predicates,
										  RouteReader routeReader) {
		this.webHandler = webHandler;
		this.routeReader = routeReader;

		predicates.forEach((name, factory) -> {
			String key = normalizeName(name);
			if (this.predicates.containsKey(key)) {
				this.logger.warn("A PredicateFactory named "+ key
						+ " already exists, class: " + this.predicates.get(key)
						+ ". It will be overwritten.");
			}
			this.predicates.put(key, factory);
			if (logger.isInfoEnabled()) {
				logger.info("Loaded PredicateFactory [" + key + "]");
			}
		});

		setOrder(-1);
	}

	private String normalizeName(String name) {
		return name.replace(PredicateFactory.class.getSimpleName(), "");
	}

	@Override
	protected void initApplicationContext() throws BeansException {
		super.initApplicationContext();
		registerHandlers(this.routeReader.getRoutes());
	}

	protected void registerHandlers(List<Route> routes) {
		this.routes = routes;
	}

	@Override
	protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
		exchange.getAttributes().put(GATEWAY_HANDLER_MAPPER_ATTR, getClass().getSimpleName());

		Route route;
		try {
			route = lookupRoute(this.routes, exchange);
		}
		catch (Exception ex) {
			return Mono.error(ex);
		}

		if (route != null) {
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Mapping [" + getExchangeDesc(exchange) + "] to " + route);
			}

			exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
			return Mono.just(this.webHandler);
		}
		else if (this.logger.isTraceEnabled()) {
			this.logger.trace("No Route found for [" + getExchangeDesc(exchange) + "]");
		}

		return Mono.empty();
	}

	//TODO: get desc from factory?
	private String getExchangeDesc(ServerWebExchange exchange) {
		StringBuilder out = new StringBuilder();
		out.append("Exchange: ");
		out.append(exchange.getRequest().getMethod());
		out.append(" ");
		out.append(exchange.getRequest().getURI());
		return out.toString();
	}


	protected Route lookupRoute(List<Route> routes, ServerWebExchange exchange) throws Exception {
		for (Route route : routes) {
			if (!route.getPredicates().isEmpty()) {
				//TODO: cache predicate
				Predicate<ServerWebExchange> predicate = combinePredicates(route);
				if (predicate.test(exchange)) {
					if (logger.isDebugEnabled()) {
						logger.debug("Route matched: " + route.getId());
					}
					validateRoute(route, exchange);
					return route;
				} else {
					if (logger.isTraceEnabled()) {
						logger.trace("Route did not match: " + route.getId());
					}
				}
			}
		}
		return null;
	}


	private Predicate<ServerWebExchange> combinePredicates(Route route) {
		List<PredicateDefinition> predicates = route.getPredicates();
		Predicate<ServerWebExchange> predicate = lookup(route, predicates.get(0));

		for (PredicateDefinition andPredicate : predicates.subList(1, predicates.size())) {
			Predicate<ServerWebExchange> found = lookup(route, andPredicate);
			predicate = predicate.and(found);
		}

		return predicate;
	}

	private Predicate<ServerWebExchange> lookup(Route route, PredicateDefinition predicate) {
		PredicateFactory found = this.predicates.get(predicate.getName());
		if (found == null) {
			throw new IllegalArgumentException("Unable to find PredicateFactory with name " + predicate.getName());
		}
		if (logger.isDebugEnabled()) {
			List<String> args;
			if (predicate.getArgs() != null) {
				args = Arrays.asList(predicate.getArgs());
			} else {
				args = Collections.emptyList();
			}
			logger.debug("Route " + route.getId() + " applying "+ predicate.getValue()
					+ ", " + args + " to " + predicate.getName());
		}
		return found.apply(predicate.getValue(), predicate.getArgs());
	}

	/**
	 * Validate the given handler against the current request.
	 * <p>The default implementation is empty. Can be overridden in subclasses,
	 * for example to enforce specific preconditions expressed in URL mappings.
	 * @param route the Route object to validate
	 * @param exchange current exchange
	 * @throws Exception if validation failed
	 */
	@SuppressWarnings("UnusedParameters")
	protected void validateRoute(Route route, ServerWebExchange exchange) throws Exception {
	}

}