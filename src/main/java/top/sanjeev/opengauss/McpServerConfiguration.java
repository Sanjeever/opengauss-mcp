package top.sanjeev.opengauss;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpServerConfiguration {

    @Bean
    public ToolCallbackProvider toolCallbackProvider(OpenGaussMcpTools openGaussMcpTools) {
        return MethodToolCallbackProvider.builder().toolObjects(openGaussMcpTools).build();
    }
}
