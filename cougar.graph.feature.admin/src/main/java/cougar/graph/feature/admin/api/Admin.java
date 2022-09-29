package cougar.graph.feature.admin.api;

import cougar.graph.api.controller.AbstractController;
import cougar.graph.feature.admin.domain.AdminServices;
import cougar.graph.store.RepositoryType;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;

@RestController
@RequestMapping(path = "/api/admin/bulk")
@Api(tags = "Admin Operations")
@Slf4j(topic = "graph.feature.admin.api")
public class Admin extends AbstractController {
    protected final AdminServices adminServices;

    public Admin(AdminServices adminServices) {
        this.adminServices = adminServices;
    }

    @ApiOperation(value = "Empty repository", tags = {})
    @GetMapping(value = "/reset", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<Void> queryBindings(@RequestParam(name = "name") String repositoryTypeName) {
        RepositoryType repositoryType;
        if (!StringUtils.hasLength(repositoryTypeName))
            repositoryType = RepositoryType.ENTITIES;
        else
            repositoryType = RepositoryType.valueOf(repositoryTypeName.toUpperCase(Locale.ROOT));

        Assert.notNull(repositoryType, "Invalid value for repository type: " + repositoryTypeName);

        return super.getAuthentication()
                .map(auth -> adminServices.reset(auth, repositoryType))
                .then()
                .doOnSubscribe(s -> log.debug("Clearing the repository"));
    }


    @ApiOperation(value = "Import RDF into entity repository", tags = {})
    @PostMapping(value = "/import/entities", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<Void> importEntities(@RequestBody Flux<DataBuffer> bytes, @RequestParam String mimetype) {
        Assert.isTrue(StringUtils.hasLength(mimetype), "Mimetype is a required parameter");

        return super.getAuthentication()
                .map(authentication -> adminServices.importEntities(bytes, mimetype, authentication)).then()
                .doOnSubscribe(s -> log.debug("Importing a file of mimetype {}", mimetype));
    }


}
