package top.sanjeev.opengauss;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.stdout.encoding", "UTF-8");
        System.setProperty("sun.stderr.encoding", "UTF-8");
        System.setOut(new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.err)), true, StandardCharsets.UTF_8));
        SpringApplication springApplication = new SpringApplication(Application.class);
        springApplication.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        springApplication.run(args);
    }

}
