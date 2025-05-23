package be.ugent.rml;

import be.ugent.idlab.knows.functions.agent.Agent;
import be.ugent.rml.extractor.ConstantExtractor;
import be.ugent.rml.extractor.HashExtractor;
import be.ugent.rml.extractor.ReferenceExtractor;
import be.ugent.rml.functions.*;
import be.ugent.rml.store.QuadStore;
import be.ugent.rml.term.Literal;
import be.ugent.rml.term.NamedNode;
import be.ugent.rml.term.Term;
import be.ugent.rml.termgenerator.BlankNodeGenerator;
import be.ugent.rml.termgenerator.LiteralGenerator;
import be.ugent.rml.termgenerator.NamedNodeGenerator;
import be.ugent.rml.termgenerator.TermGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static be.ugent.rml.Utils.getObjectsFromQuads;
import static be.ugent.rml.Utils.isValidrrLanguage;

public class MappingFactory {
    private final Agent functionAgent;
    private MappingInfo subjectMappingInfo;
    private List<MappingInfo> graphMappingInfos;
    private Term triplesMap;
    private QuadStore store;
    private List<PredicateObjectGraphMapping> predicateObjectGraphMappings;
    // This boolean is true when the double in a reference need to be ignored.
    // For example, when accessing data in a RDB.
    private boolean ignoreDoubleQuotes;

    // Base IRI to prepend to a relative IRI to make it absolute.
    private final String baseIRI;

    // check on logical source is need on more than one place, so better store it
    private Term logicalSource;

    // StrictMode determines RMLMapper's behaviour when an IRI for a NamedNode is invalid.
    // If set to BEST_EFFORT, RMLMapper will not generate a NamedNode and go on.
    // If set to STRICT, RMLMapper will stop execution with an exception.
    private final StrictMode strictMode;

    protected Logger logger = LoggerFactory.getLogger(this.getClass());

    public MappingFactory(final Agent functionAgent, final String baseIRI, final StrictMode strictMode) {
        this.functionAgent = functionAgent;
        this.baseIRI = baseIRI;
        this.strictMode = strictMode;
    }

    public Mapping createMapping(Term triplesMap, QuadStore store) throws Exception {
        this.triplesMap = triplesMap;
        this.store = store;
        this.subjectMappingInfo = null;
        this.predicateObjectGraphMappings = new ArrayList<>();
        this.graphMappingInfos = null;
        this.ignoreDoubleQuotes = this.areDoubleQuotesIgnored(store, triplesMap);

        parseSubjectMap();
        parsePredicateObjectMaps();
        graphMappingInfos = parseGraphMapsAndShortcuts(subjectMappingInfo.getTerm());


        //return the mapping
        return new Mapping(subjectMappingInfo, predicateObjectGraphMappings, graphMappingInfos);
    }

    private void parseSubjectMap() throws Exception {
        if (this.subjectMappingInfo == null) {
            TermGenerator generator;
            List<Term> subjectmaps = getObjectsFromQuads(store.getQuads(triplesMap, new NamedNode(NAMESPACES.RML2 + "subject"), null));
            subjectmaps.addAll(getObjectsFromQuads(store.getQuads(triplesMap, new NamedNode(NAMESPACES.RML2 + "subjectMap"), null)));

            if (!subjectmaps.isEmpty()) {
                if (subjectmaps.size() > 1) {
                    throw new Exception(String.format("%s has %d Subject Maps. You can only have one.", triplesMap, subjectmaps.size()));
                }

                Term subjectmap = subjectmaps.get(0);
                List<Term> functionValues = getObjectsFromQuads(store.getQuads(subjectmap, new NamedNode(NAMESPACES.FNML + "functionValue"), null));
                List<Term> termTypes = getObjectsFromQuads(store.getQuads(subjectmap, new NamedNode(NAMESPACES.RML2 + "termType"), null));

                if (termTypes.contains(new NamedNode(NAMESPACES.RML2 + "Literal"))) {
                    throw new Exception(triplesMap + " is a Literal Term Map. Accepted term types for Subject Maps are: IRI, Blank Node");
                }

                boolean isBlankNode = !termTypes.isEmpty() && termTypes.get(0).equals(new NamedNode(NAMESPACES.RML2  + "BlankNode"));

                if (functionValues.isEmpty()) {
                    //checking if we are dealing with a Blank Node as subject
                    if (isBlankNode) {
                        SingleRecordFunctionExecutor executor = RecordFunctionExecutorFactory.generate(store, subjectmap, true, ignoreDoubleQuotes, strictMode.equals(StrictMode.STRICT));

                        if (executor != null) {
                            generator = new BlankNodeGenerator(executor);
                        } else {
                            generator = new BlankNodeGenerator();
                        }
                    } else {
                        //we are not dealing with a Blank Node, so we create the template
                        generator = new NamedNodeGenerator(RecordFunctionExecutorFactory.generate(store, subjectmap, true, ignoreDoubleQuotes, strictMode.equals(StrictMode.STRICT)), baseIRI, strictMode);
                    }
                } else {
                    SingleRecordFunctionExecutor functionExecutor = parseFunctionTermMap(functionValues.get(0));

                    if (isBlankNode) {
                        generator = new BlankNodeGenerator(functionExecutor);
                    } else {
                        generator = new NamedNodeGenerator(functionExecutor, baseIRI, strictMode);
                    }
                }

                // get targets for subject
                // get Target Generators for subject
                List<Term> targets = getTargets(subjectmap);
                List<TermGenerator> targetGenerators = getTargetGenerators(subjectmap, baseIRI, strictMode);
                this.subjectMappingInfo = new MappingInfo(subjectmap, generator, targets, targetGenerators);

                //get classes
                List<Term> classes = getObjectsFromQuads(store.getQuads(subjectmap, new NamedNode(NAMESPACES.RML2 + "class"), null));

                //we create predicateobjects for the classes
                for (Term c : classes) {
                    /*
                     * Don't put in graph for rr:class, subject is already put in graph, otherwise double export.
                     * Same holds for targets, the rdf:type triple will be exported to the subject target already.
                     */
                    NamedNodeGenerator predicateGenerator = new NamedNodeGenerator(new ConstantExtractor(NAMESPACES.RDF + "type"), baseIRI, strictMode);
                    NamedNodeGenerator objectGenerator = new NamedNodeGenerator(new ConstantExtractor(c.getValue()), baseIRI, strictMode);
                    predicateObjectGraphMappings.add(new PredicateObjectGraphMapping(
                            new MappingInfo(subjectmap, predicateGenerator),
                            new MappingInfo(subjectmap, objectGenerator),
                            null, null));
                }
            } else {
                throw new Exception(triplesMap + " has no Subject Map. Each Triples Map should have exactly one Subject Map.");
            }
        }
    }

    private void parsePredicateObjectMaps() throws Exception {
        List<Term> predicateobjectmaps = getObjectsFromQuads(store.getQuads(triplesMap, new NamedNode(NAMESPACES.RML2 + "predicateObjectMap"), null));

        for (Term pom : predicateobjectmaps) {
            List<MappingInfo> predicateMappingInfos = parsePredicateMapsAndShortcuts(pom);
            List<MappingInfo> graphMappingInfos = parseGraphMapsAndShortcuts(pom);

            parseObjectMapsAndShortcutsAndGeneratePOGGenerators(pom, predicateMappingInfos, graphMappingInfos);
        }
    }

    private void parseObjectMapsAndShortcutsAndGeneratePOGGenerators(Term termMap, List<MappingInfo> predicateMappingInfos, List<MappingInfo> graphMappingInfos) throws IOException {
        parseObjectMapsAndShortcutsWithCallback(termMap, (oMappingInfo, childOrParent) -> {
            MappingInfo lMappingInfo = parseLanguageMappingInfo(oMappingInfo.getTerm());

            predicateMappingInfos.forEach(pMappingInfo -> {
                if (graphMappingInfos.isEmpty()) {
                    predicateObjectGraphMappings.add(new PredicateObjectGraphMapping(pMappingInfo, oMappingInfo, null, lMappingInfo));
                } else {
                    graphMappingInfos.forEach(gMappingInfo -> {
                        predicateObjectGraphMappings.add(new PredicateObjectGraphMapping(pMappingInfo, oMappingInfo, gMappingInfo, lMappingInfo));
                    });
                }
            });
        }, (parentTriplesMap, joinConditionFunctionExecutors) -> {
            predicateMappingInfos.forEach(pMappingInfo -> {
                List<PredicateObjectGraphMapping> pos = getPredicateObjectGraphMappingFromMultipleGraphMappingInfos(pMappingInfo, null, graphMappingInfos);

                pos.forEach(pogMappingInfo -> {
                    pogMappingInfo.setParentTriplesMap(parentTriplesMap);

                    joinConditionFunctionExecutors.forEach(jcfe -> {
                        pogMappingInfo.addJoinCondition(jcfe);
                    });

                    predicateObjectGraphMappings.add(pogMappingInfo);
                });
            });
        });
    }

    private void parseObjectMapsAndShortcutsWithCallback(Term termMap, BiConsumer<MappingInfo, String> objectMapCallback, BiConsumer<Term, List<MultipleRecordsFunctionExecutor>> refObjectMapCallback) throws IOException {
        List<Term> objectmaps = getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RML2 + "objectMap"), null));

        for (Term objectmap : objectmaps) {
            parseObjectMapWithCallback(objectmap, objectMapCallback, refObjectMapCallback);
        }

        //dealing with rr:object
        List<Term> objectsConstants = getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RML2 + "object"), null));

        for (Term o : objectsConstants) {
            TermGenerator gen;
            SingleRecordFunctionExecutor fn = new ConstantExtractor(o.getValue());

            if (o instanceof Literal) {
                if (((Literal) o).getDatatype() != null) {
                    Term datatype = new NamedNode(((Literal) o).getDatatype().toString());
                    gen = new LiteralGenerator(fn, datatype);
                } else if (((Literal) o).getLanguage().isPresent()) {
                    SingleRecordFunctionExecutor executor = new ConstantExtractor(((Literal) o).getLanguage().get());
                    gen = new LiteralGenerator(fn, executor);
                } else {
                    gen = new LiteralGenerator(fn);
                }

            } else {
                gen = new NamedNodeGenerator(fn, baseIRI, strictMode);
            }

            // rr:object shortcut can never have targets
            objectMapCallback.accept(new MappingInfo(termMap, gen), "child");
        }
    }

    private void parseObjectMapWithCallback(Term objectmap, BiConsumer<MappingInfo, String> objectMapCallback, BiConsumer<Term, List<MultipleRecordsFunctionExecutor>> refObjectMapCallback) throws IOException {
        List<Term> functionValues = getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.FNML + "functionValue"), null));
        Term termType = getTermType(objectmap, true);

        List<Term> datatypes = getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.RML2 + "datatype"), null));
        List<Term> parentTriplesMaps = getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.RML2 + "parentTriplesMap"), null));
        List<Term> parentTermMaps = getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.RML2 + "parentTermMap"), null));

        List<SingleRecordFunctionExecutor> languages = getLanguageExecutorsForObjectMap(objectmap);

        if (functionValues.isEmpty()) {
            boolean encodeIRI = termType != null && termType.getValue().equals(NAMESPACES.RML2 + "IRI");
            SingleRecordFunctionExecutor executor = RecordFunctionExecutorFactory.generate(store, objectmap, encodeIRI, ignoreDoubleQuotes, strictMode.equals(StrictMode.STRICT));

            if (parentTriplesMaps.isEmpty() && parentTermMaps.isEmpty()) {
                TermGenerator oGen;

                if (termType.equals(new NamedNode(NAMESPACES.RML2 + "Literal"))) {
                    //check if we need to apply a datatype to the object
                    if (!datatypes.isEmpty()) {
                        oGen = new LiteralGenerator(executor, datatypes.get(0));
                        //check if we need to apply a language to the object
                    } else if (!languages.isEmpty()) {
                        oGen = new LiteralGenerator(executor, languages.get(0));
                    } else {
                        oGen = new LiteralGenerator(executor);
                    }
                } else if (termType.equals(new NamedNode(NAMESPACES.RML2 + "IRI"))) {
                    oGen = new NamedNodeGenerator(executor, baseIRI, strictMode);
                } else {
                    if (executor == null) {
                        // This will generate Blank Node with random identifiers.
                        oGen = new BlankNodeGenerator();
                    } else {
                        oGen = new BlankNodeGenerator(executor);
                    }
                }

                // get language maps targets for object map
                // TODO why is this here?
                MappingInfo languageMapInfo = null;
                List<Term> languageMaps = getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.RML2 + "languageMap"), null));

                // get targets and targetGenerators for object map
                List<Term> oTargets = getTargets(objectmap);
                List<TermGenerator> oTargetGenerators = getTargetGenerators(objectmap, baseIRI, strictMode);

                objectMapCallback.accept(new MappingInfo(objectmap, oGen, oTargets, oTargetGenerators), "child");
            } else if (!parentTriplesMaps.isEmpty()) {
                if (parentTriplesMaps.size() > 1) {
                    logger.warn("{} has {} Parent Triples Maps. You can only have one. A random one is taken.", triplesMap, parentTriplesMaps.size());
                }

                Term parentTriplesMap = parentTriplesMaps.get(0);

                List<Term> rmljoinConditions = getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.RML + "joinCondition"), null));
                List<MultipleRecordsFunctionExecutor> joinConditionFunctionExecutors = new ArrayList<>();

                List<Term> joinConditions = getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.RML2 + "joinCondition"), null));

                for (Term joinCondition : joinConditions) {

                    List<String> parents = Utils.getLiteralObjectsFromQuads(store.getQuads(joinCondition, new NamedNode(NAMESPACES.RML2 + "parent"), null));
                    List<String> childs = Utils.getLiteralObjectsFromQuads(store.getQuads(joinCondition, new NamedNode(NAMESPACES.RML2 + "child"), null));

                    if (parents.isEmpty()) {
                        throw new Error("One of the join conditions of " + triplesMap + " is missing rr:parent.");
                    } else if (childs.isEmpty()) {
                        throw new Error("One of the join conditions of " + triplesMap + " is missing rr:child.");
                    } else {
                        Map<String, Object[]> parameters = new HashMap<>();

                        boolean ignoreDoubleQuotesInParent = this.areDoubleQuotesIgnored(store, parentTriplesMap);
                        SingleRecordFunctionExecutor parent = new ReferenceExtractor(parents.get(0), ignoreDoubleQuotesInParent, strictMode.equals(StrictMode.STRICT));
                        Object[] detailsParent = {"parent", parent};
                        parameters.put("http://users.ugent.be/~bjdmeest/function/grel.ttl#valueParameter", detailsParent);

                        SingleRecordFunctionExecutor child = new ReferenceExtractor(childs.get(0), ignoreDoubleQuotes, strictMode.equals(StrictMode.STRICT));
                        Object[] detailsChild = {"child", child};
                        parameters.put("http://users.ugent.be/~bjdmeest/function/grel.ttl#valueParameter2", detailsChild);

                        joinConditionFunctionExecutors.add(new StaticMultipleRecordsFunctionExecutor(parameters, functionAgent, "https://w3id.org/imec/idlab/function#equal"));
                    }
                }

                for (Term joinCondition : rmljoinConditions) {
                    // TODO fix this for KGC_fnml
                    Term functionValue = getObjectsFromQuads(store.getQuads(joinCondition, new NamedNode(NAMESPACES.FNML + "functionValue"), null)).get(0);
                    joinConditionFunctionExecutors.add(parseJoinConditionFunctionTermMap(functionValue));
                }

                // get logical source of parentTriplesMap
                List<Term> logicalSources = getObjectsFromQuads(store.getQuads(this.triplesMap, new NamedNode(NAMESPACES.RML2 + "logicalSource"), null));
                Term logicalSource = null;
                if (!logicalSources.isEmpty()) {
                    logicalSource = logicalSources.get(0);
                }

                List<Term> parentLogicalSources = getObjectsFromQuads(store.getQuads(parentTriplesMap, new NamedNode(NAMESPACES.RML2 + "logicalSource"), null));
                Term parentLogicalSource = null;
                if (!parentLogicalSources.isEmpty()) {
                    parentLogicalSource = parentLogicalSources.get(0);
                }
                // Check if there is at least one Logical Source.
                if (refObjectMapCallback != null) {
                    refObjectMapCallback.accept(parentTriplesMap, joinConditionFunctionExecutors);
                }
            } else if (!parentTermMaps.isEmpty()) {
                parseObjectMapWithCallback(parentTermMaps.get(0), (objectGenerator, childOrParent) -> {
                    objectMapCallback.accept(objectGenerator, "parent");
                }, null);
            }
        } else {
            SingleRecordFunctionExecutor functionExecutor = parseFunctionTermMap(functionValues.get(0));
            TermGenerator gen;

            //TODO is literal the default?
            if (termType == null || termType.equals(new NamedNode(NAMESPACES.RML2 + "Literal"))) {
                //check if we need to apply a datatype to the object
                if (!datatypes.isEmpty()) {
                    gen = new LiteralGenerator(functionExecutor, datatypes.get(0));
                    //check if we need to apply a language to the object
                } else if (!languages.isEmpty()) {
                    gen = new LiteralGenerator(functionExecutor, languages.get(0));
                } else {
                    gen = new LiteralGenerator(functionExecutor);
                }
            } else {
                gen = new NamedNodeGenerator(functionExecutor, baseIRI, strictMode);
            }

            // get targets and targetGenerators for object map
            List<Term> targets = getTargets(objectmap);
            List<TermGenerator> targetGenerators = getTargetGenerators(objectmap, baseIRI, strictMode);

            objectMapCallback.accept(new MappingInfo(objectmap, gen, targets, targetGenerators), "child");

        }
    }

    private List<MappingInfo> parseGraphMapsAndShortcuts(Term termMap) throws Exception {
        List<MappingInfo> graphMappingInfos = new ArrayList<>();

        List<Term> graphMaps = getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RML2 + "graphMap"), null));

        for (Term graphMap : graphMaps) {
            List<Term> functionValues = getObjectsFromQuads(store.getQuads(graphMap, new NamedNode(NAMESPACES.FNML + "functionValue"), null));
            List<Term> termTypes = getObjectsFromQuads(store.getQuads(graphMap, new NamedNode(NAMESPACES.RML2 + "termType"), null));
            Term termType = null;

            if (!termTypes.isEmpty()) {
                termType = termTypes.get(0);

                if (termType.equals(new NamedNode(NAMESPACES.RML2 + "Literal"))) {
                    throw new Exception("A Graph Map cannot generate literals.");
                }
            }

            TermGenerator generator;

            if (functionValues.isEmpty()) {
                SingleRecordFunctionExecutor executor = RecordFunctionExecutorFactory.generate(store, graphMap, true, ignoreDoubleQuotes, strictMode.equals(StrictMode.STRICT));

                if (termType == null || termType.equals(new NamedNode(NAMESPACES.RML2 + "IRI"))) {
                    generator = new NamedNodeGenerator(executor, baseIRI, strictMode);
                } else {
                    if (executor == null) {
                        generator = new BlankNodeGenerator();
                    } else {
                        generator = new BlankNodeGenerator(executor);
                    }
                }
            } else {
                SingleRecordFunctionExecutor functionExecutor = parseFunctionTermMap(functionValues.get(0));

                if (termType == null || termType.equals(new NamedNode(NAMESPACES.RML2 + "IRI"))) {
                    generator = new NamedNodeGenerator(functionExecutor, baseIRI, strictMode);
                } else {
                    generator = new BlankNodeGenerator(functionExecutor);
                }
            }

            // get targets and target generators for graph map
            List<Term> targets = getTargets(graphMap);
            List<TermGenerator> targetGenerators = getTargetGenerators(graphMap, baseIRI, strictMode);

            graphMappingInfos.add(new MappingInfo(termMap, generator, targets, targetGenerators));

        }

        List<Term> graphShortcuts = getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RML2 + "graph"), null));

        for (Term graph : graphShortcuts) {
            String gStr = graph.getValue();
            // rr:graph shortcut can never have targets
            graphMappingInfos.add(new MappingInfo(termMap, new NamedNodeGenerator(new ConstantExtractor(gStr), baseIRI, strictMode)));
        }

        return graphMappingInfos;
    }

    private List<MappingInfo> parsePredicateMapsAndShortcuts(Term termMap) throws IOException {
        List<MappingInfo> predicateMappingInfos = new ArrayList<>();

        List<Term> predicateMaps = getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RML2 + "predicateMap"), null));

        for (Term predicateMap : predicateMaps) {
            // get functionValue for predicate maps
            List<Term> functionValues = getObjectsFromQuads(store.getQuads(predicateMap, new NamedNode(NAMESPACES.FNML + "functionValue"), null));

            // get target generators for predicate maps
            List<Term> targets = getTargets(predicateMap);
            List<TermGenerator> targetGenerators = getTargetGenerators(predicateMap, baseIRI, strictMode);

            if (functionValues.isEmpty()) {
                predicateMappingInfos.add(new MappingInfo(predicateMap,
                        new NamedNodeGenerator(RecordFunctionExecutorFactory.generate(store, predicateMap, false, ignoreDoubleQuotes, strictMode.equals(StrictMode.STRICT)), baseIRI, strictMode),
                        targets, targetGenerators));
            } else {
                SingleRecordFunctionExecutor functionExecutor = parseFunctionTermMap(functionValues.get(0));

                predicateMappingInfos.add(new MappingInfo(predicateMap, new NamedNodeGenerator(functionExecutor, baseIRI, strictMode), targets, targetGenerators));
            }
        }

        List<Term> predicateShortcuts = getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RML2 + "predicate"), null));

        for (Term predicate : predicateShortcuts) {
            String pStr = predicate.getValue();
            // rr:predicate shortcut can never have targets
            predicateMappingInfos.add(new MappingInfo(termMap, new NamedNodeGenerator(new ConstantExtractor(pStr), baseIRI, strictMode)));
        }

        return predicateMappingInfos;
    }

    private SingleRecordFunctionExecutor parseFunctionTermMap(Term functionValue) throws IOException {
        List<Term> functionPOMs = getObjectsFromQuads(store.getQuads(functionValue, new NamedNode(NAMESPACES.RML2 + "predicateObjectMap"), null));
        ArrayList<ParameterValuePair> params = new ArrayList<>();

        for (Term pom : functionPOMs) {
            List<MappingInfo> pMappingInfos = parsePredicateMapsAndShortcuts(pom);
            List<MappingInfo> oMappingInfos = parseObjectMapsAndShortcuts(pom);

            List<TermGenerator> pGenerators = new ArrayList<>();
            pMappingInfos.forEach(mappingInfo -> {
                pGenerators.add(mappingInfo.getTermGenerator());
            });

            List<TermGenerator> oGenerators = new ArrayList<>();
            oMappingInfos.forEach(mappingInfo -> {
                oGenerators.add(mappingInfo.getTermGenerator());
            });

            params.add(new ParameterValuePair(pGenerators, oGenerators));
        }

        return new DynamicSingleRecordFunctionExecutor(params, functionAgent);
    }

    private MultipleRecordsFunctionExecutor parseJoinConditionFunctionTermMap(Term functionValue) throws IOException {
        List<Term> functionPOMs = getObjectsFromQuads(store.getQuads(functionValue, new NamedNode(NAMESPACES.RML2 + "predicateObjectMap"), null));
        ArrayList<ParameterValueOriginPair> params = new ArrayList<>();

        for (Term pom : functionPOMs) {
            List<MappingInfo> pMappingInfos = parsePredicateMapsAndShortcuts(pom);

            List<TermGenerator> pGenerators = new ArrayList<>();
            pMappingInfos.forEach(mappingInfo -> {
                pGenerators.add(mappingInfo.getTermGenerator());
            });

            ArrayList<TermGeneratorOriginPair> objectGeneratorOriginPairs = new ArrayList<>();
            parseObjectMapsAndShortcutsWithCallback(pom, (oGen, childOrParent) -> {
                objectGeneratorOriginPairs.add(new TermGeneratorOriginPair(oGen.getTermGenerator(), childOrParent));
            }, null);

            params.add(new ParameterValueOriginPair(pGenerators, objectGeneratorOriginPairs));
        }

        return new DynamicMultipleRecordsFunctionExecutor(params, functionAgent);
    }

    /**
     * Generate a join condition that only returns true if the same record hash is encountered
     * @return
     * @throws IOException
     */
    private MultipleRecordsFunctionExecutor generateSameLogicalSourceJoinConditionFunctionTermMap() throws IOException {
        Map<String, Object[]> parameters = new HashMap<>();

        SingleRecordFunctionExecutor parent = new HashExtractor();
        Object[] detailsParent = {"parent", parent};
        parameters.put("http://users.ugent.be/~bjdmeest/function/grel.ttl#valueParameter", detailsParent);

        SingleRecordFunctionExecutor child = new HashExtractor();
        Object[] detailsChild = {"child", child};
        parameters.put("http://users.ugent.be/~bjdmeest/function/grel.ttl#valueParameter2", detailsChild);

        return new StaticMultipleRecordsFunctionExecutor(parameters, functionAgent, "https://w3id.org/imec/idlab/function#equal");
    }

    private List<MappingInfo> parseObjectMapsAndShortcuts(Term pom) throws IOException {
        List<MappingInfo> mappingInfos = new ArrayList<>();

        parseObjectMapsAndShortcutsWithCallback(pom, (mappingInfo, childOrParent) -> {
            mappingInfos.add(mappingInfo);
        }, (term, joinConditionFunctions) -> {
        });

        return mappingInfos;
    }

    /**
     * This method returns all executors for the languages of an Object Map.
     * @param objectmap the object for which the executors need to be determined.
     * @return a list of executors that return language tags.
     */
    private List<SingleRecordFunctionExecutor> getLanguageExecutorsForObjectMap(Term objectmap) throws IOException {
        ArrayList<SingleRecordFunctionExecutor> executors = new ArrayList<>();

        // Parse rr:language
        List<Term> languages = getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.RML2 + "language"), null));

        // Validate languages.
        languages.stream().map(Term::getValue).forEach(language -> {if (! isValidrrLanguage(language)) {
            throw new RuntimeException(String.format("Language tag \"%s\" does not conform to BCP 47 standards", language));
        }});

        for (Term language: languages) {
            executors.add(new ConstantExtractor(language.getValue()));
        }

        // Parse rml:languageMap
        List<Term> languageMaps = getObjectsFromQuads(store.getQuads(objectmap, new NamedNode(NAMESPACES.RML2 + "languageMap"), null));

        for (Term languageMap : languageMaps) {
            List<Term> functionValues = getObjectsFromQuads(store.getQuads(languageMap, new NamedNode(NAMESPACES.FNML + "functionValue"), null));

            if (functionValues.isEmpty()) {
                executors.add(RecordFunctionExecutorFactory.generate(store, languageMap, false, ignoreDoubleQuotes, strictMode.equals(StrictMode.STRICT)));
            } else {
                executors.add(parseFunctionTermMap(functionValues.get(0)));
            }
        }

        return executors;
    }

    private MappingInfo parseLanguageMappingInfo(Term objectMap) {
        // get optional language map targets for object map
        MappingInfo mappingInfo = null;

        if(objectMap == null) {
            return mappingInfo;
        }

        List<Term> languageMaps = getObjectsFromQuads(store.getQuads(objectMap, new NamedNode(NAMESPACES.RML2 + "languageMap"), null));
        if (languageMaps.size() == 1) {
            Term l = languageMaps.get(0);
            List<Term> lTargets = getTargets(l);
            List<TermGenerator> lTargetGenerators = getTargetGenerators(l, baseIRI, strictMode);
            mappingInfo = new MappingInfo(l, lTargets, lTargetGenerators);
        }
        else if (languageMaps.size() > 1) {
            logger.warn("Multiple language maps found, a random language map is used");
        }
        return mappingInfo;
    }

    /**
     * This method returns the TermType of a given Term Map.
     * If no Term Type is found, a default Term Type is return based on the R2RML specification.
     **/
    private Term getTermType(Term map, boolean isObjectMap) {
        List<Term> termTypes = getObjectsFromQuads(store.getQuads(map, new NamedNode(NAMESPACES.RML2 + "termType"), null));

        Term termType = null;

        if (!termTypes.isEmpty()) {
            termType = termTypes.get(0);
        } else {
            List<Term> constants = getObjectsFromQuads(store.getQuads(map, new NamedNode(NAMESPACES.RML2 + "constant"), null));

            if (!constants.isEmpty()) {
                Term constant = constants.get(0);

                if (constant instanceof Literal) {
                    termType = new NamedNode(NAMESPACES.RML2 + "Literal");
                } else if (constant instanceof NamedNode) {
                    termType = new NamedNode(NAMESPACES.RML2 + "IRI");
                } else {
                    termType = new NamedNode(NAMESPACES.RML2 + "BlankNode");
                }
            } else if (isObjectMap) {
                boolean hasReference = !getObjectsFromQuads(store.getQuads(map, new NamedNode(NAMESPACES.RML2 + "reference"), null)).isEmpty();
                boolean hasFunctionValues = !getObjectsFromQuads(store.getQuads(map, new NamedNode(NAMESPACES.FNML + "functionValue"), null)).isEmpty();
                boolean hasLanguage = !getObjectsFromQuads(store.getQuads(map, new NamedNode(NAMESPACES.RML2 + "language"), null)).isEmpty() ||
                        !getObjectsFromQuads(store.getQuads(map, new NamedNode(NAMESPACES.RML2 + "languageMap"), null)).isEmpty();
                boolean hasDatatype = !getObjectsFromQuads(store.getQuads(map, new NamedNode(NAMESPACES.RML2 + "datatype"), null)).isEmpty();

                if (hasReference || hasLanguage || hasDatatype || hasFunctionValues) {
                    termType = new NamedNode(NAMESPACES.RML2 + "Literal");
                } else {
                    termType = new NamedNode(NAMESPACES.RML2 + "IRI");
                }
            } else {
                termType = new NamedNode(NAMESPACES.RML2 + "IRI");
            }
        }

        return termType;
    }

    private List<PredicateObjectGraphMapping> getPredicateObjectGraphMappingFromMultipleGraphMappingInfos(MappingInfo pMappingInfo, MappingInfo oMappingInfo, List<MappingInfo> gMappingInfos) {
        ArrayList<PredicateObjectGraphMapping> list = new ArrayList<>();
        MappingInfo lMappingInfo = null;
        if(oMappingInfo != null) {
            lMappingInfo = parseLanguageMappingInfo(oMappingInfo.getTerm());
        }

        for(MappingInfo gMappingInfo: gMappingInfos) {
            list.add(new PredicateObjectGraphMapping(pMappingInfo, oMappingInfo, gMappingInfo, lMappingInfo));
        }

        if (gMappingInfos.isEmpty()) {
            list.add(new PredicateObjectGraphMapping(pMappingInfo, oMappingInfo, null, lMappingInfo));
        }

        return list;
    }

    /**
     * This function returns true if double quotes should be ignored in references.
     * @param store The store with the RML rules.
     * @param triplesMap The Triples Map that should be checked.
     * @return true if double quotes should be ignored in references, else false.
     */
    private boolean areDoubleQuotesIgnored(QuadStore store, Term triplesMap) {
        List<Term> logicalSources = getObjectsFromQuads(store.getQuads(triplesMap, new NamedNode(NAMESPACES.RML2 + "logicalSource"), null));

        if (!logicalSources.isEmpty()) {
            Term logicalSource = logicalSources.get(0);

            List<Term> sources = getObjectsFromQuads(store.getQuads(logicalSource, new NamedNode(NAMESPACES.RML2 + "source"), null));

            if (!sources.isEmpty()) {
                Term source = sources.get(0);

                if (! (sources.get(0) instanceof Literal)) {
                    List<Term> sourceType = getObjectsFromQuads(store.getQuads(source, new NamedNode(NAMESPACES.RDF + "type"), null));

                    return sourceType.get(0).getValue().equals(NAMESPACES.D2RQ + "Database");
                }
            }
        }

        return false;
    }

    private List<TermGenerator> getTargetGenerators(Term termMap, String baseIRI, StrictMode strictMode) {
        List<TermGenerator> targetGenerators = new ArrayList<>();
        List<Term> logicalTargetMaps = Utils.getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RMLE + "logicalTargetMap"), null));
        for (Term logicalTargetMap : logicalTargetMaps) {
            SingleRecordFunctionExecutor functionExecutor = null;
            List<Term> functionValues = getObjectsFromQuads(store.getQuads(logicalTargetMap, new NamedNode(NAMESPACES.FNML + "functionValue"), null));
            if (functionValues.isEmpty()) {
                //similar to subjects, dynamic targets should always be uri
                functionExecutor = RecordFunctionExecutorFactory.generate(store, logicalTargetMap, true, ignoreDoubleQuotes, strictMode.equals(StrictMode.STRICT));
            } else {
                try {
                    functionExecutor = parseFunctionTermMap(functionValues.get(0));
                } catch (IOException e) {
                    logger.error("Parsing function term map failed:" + e);
                }
            }
            if (functionValues != null) {
                targetGenerators.add(new NamedNodeGenerator(functionExecutor, baseIRI, strictMode));
            }
        }
        return targetGenerators;
    }

    private List<Term> getTargets(Term termMap){
        List<Term> targets = new ArrayList<>();
        List<Term> logicalTargets = getObjectsFromQuads(store.getQuads(termMap, new NamedNode(NAMESPACES.RML2 + "logicalTarget"), null));
        targets.addAll(logicalTargets);
        return targets;
    }

}
