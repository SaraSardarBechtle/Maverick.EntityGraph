package cougar.graph.store.rdf4j.repository;

import cougar.graph.model.vocabulary.Local;
import cougar.graph.model.vocabulary.SDO;
import cougar.graph.model.vocabulary.Transactions;
import cougar.graph.store.RepositoryType;
import cougar.graph.store.SchemaStore;
import cougar.graph.store.rdf4j.repository.util.AbstractRepository;
import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.model.util.Namespaces;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Component
public class SchemaRepository extends AbstractRepository implements SchemaStore {

    private final static Set<Namespace> namespaces;

    static {
        namespaces = new HashSet<>(Namespaces.DEFAULT_RDF4J);
        namespaces.add(Transactions.NS);
        namespaces.add(Local.Entities.NS);
        namespaces.add(Local.Transactions.NS);
        namespaces.add(Local.Versions.NS);
        namespaces.add(SDO.NS);
    }


    public SchemaRepository() {
        super(RepositoryType.SCHEMA);
    }


    @Override
    public Optional<Namespace> getNamespaceFor(String prefix) {
        return namespaces.stream().filter(ns -> ns.getPrefix().equalsIgnoreCase(prefix)).findFirst();
    }

}
