package io.github.kingsword09.kwebshell.bridge

public class KWebBridgeRoute(
    methods: Set<String>,
    public val dispatcher: KWebBridgeDispatcher,
) {
    public val methods: Set<String> = methods.toSet()

    init {
        require(this.methods.isNotEmpty()) { "A bridge route must own at least one method." }
        this.methods.sorted().forEach { method ->
            require(METHOD_PATTERN.matches(method)) {
                "Bridge route method '$method' is invalid."
            }
        }
    }

    private companion object {
        val METHOD_PATTERN: Regex = Regex("[a-z][A-Za-z0-9]{0,63}")
    }
}

public object KWebBridgeDispatchers {
    public fun exact(vararg routes: KWebBridgeRoute): KWebBridgeDispatcher = exact(routes.asList())

    public fun exact(routes: List<KWebBridgeRoute>): KWebBridgeDispatcher {
        require(routes.isNotEmpty()) { "A composite bridge dispatcher requires at least one route." }
        val dispatchers = linkedMapOf<String, KWebBridgeDispatcher>()
        routes.forEach { route ->
            route.methods.sorted().forEach { method ->
                require(dispatchers.putIfAbsent(method, route.dispatcher) == null) {
                    "Bridge method '$method' is owned by more than one route."
                }
            }
        }
        return ExactBridgeDispatcher(dispatchers.toMap())
    }
}

private class ExactBridgeDispatcher(
    private val routes: Map<String, KWebBridgeDispatcher>,
) : KWebBridgeDispatcher {
    override suspend fun dispatch(requestJson: String): String {
        val method = KWebBridgeProtocol.decodeRequest(requestJson).method
        val dispatcher = routes[method] ?: throw KWebBridgeException(
            code = "bridge.method.unknown",
            message = "Unknown bridge method '$method'.",
        )
        return dispatcher.dispatch(requestJson)
    }
}
