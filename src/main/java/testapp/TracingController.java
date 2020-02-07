package testapp;

import brave.Tracing;
import brave.internal.PredefinedPropagationFields;
import brave.propagation.TraceContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TracingController {

    @GetMapping("parent-id")
    public String parentId() {
        TraceContext context = Tracing.currentTracer().currentSpan().context();
        return context.parentIdString();
    }

    @GetMapping("hci-trace")
    public String hciTrace() {
        TraceContext context = Tracing.currentTracer().currentSpan().context();
        return ((PredefinedPropagationFields) context.extra().get(0)).get("hcitrace");
    }
}
