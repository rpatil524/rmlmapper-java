package be.ugent.rml.functions;

import be.ugent.idlab.knows.dataio.record.Record;
import be.ugent.idlab.knows.functions.agent.Agent;
import be.ugent.idlab.knows.functions.agent.Arguments;
import be.ugent.rml.NAMESPACES;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

public class DynamicMultipleRecordsFunctionExecutor implements MultipleRecordsFunctionExecutor {

    private static final ValueFactory valueFactory = SimpleValueFactory.getInstance();


    private static final Logger logger = LoggerFactory.getLogger(DynamicMultipleRecordsFunctionExecutor.class);
    private final List<ParameterValueOriginPair> parameterValuePairs;
    private final Map<String, List<Value>> parametersCache = new HashMap<>();

    private final Agent functionAgent;

    public DynamicMultipleRecordsFunctionExecutor(final List<ParameterValueOriginPair> parameterValuePairs, final Agent functionAgent) {
        this.parameterValuePairs = parameterValuePairs;
        this.functionAgent = functionAgent;
    }

    @Override
    public Object execute(Map<String, Record> records) throws Exception {
        final ArrayList<Value> fnTerms = new ArrayList<>();
        final Arguments arguments = new Arguments();
        final Record child = records.get("child");

        parameterValuePairs.forEach(pv -> {
            ArrayList<Value> parameters = new ArrayList<>();
            ArrayList<Value> values = new ArrayList<>();

            pv.getParameterGenerators().forEach(parameterGen -> {
                try {
                    parameters.addAll(parameterGen.generate(child));
                } catch (IllegalArgumentException e) {
                    logger.error(e.getMessage());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            pv.getValueGeneratorPairs().forEach(pair -> {
                try {
                    values.addAll(pair.getTermGenerator().generate(records.get(pair.getOrigin())));
                } catch (IllegalArgumentException e) {
                    logger.error(e.getMessage());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            if (parameters.contains(valueFactory.createIRI(NAMESPACES.FNO + "executes")) || parameters.contains(valueFactory.createIRI(NAMESPACES.FNO_S + "executes"))) {
                if (parameters.contains(valueFactory.createIRI(NAMESPACES.FNO + "executes"))) {
                    logger.warn("http is used instead of https for {}. Still works for now, but will be deprecated in the future.", NAMESPACES.FNO_S);
                }
                fnTerms.add(values.get(0));
            } else {
                for (Value parameter : parameters) {
                    for (Value value : values) {
                        arguments.add(parameter.stringValue(), value.stringValue());
                    }
                }
            }
        });

        if (fnTerms.isEmpty()) {
            throw new Exception("No function was defined for parameters: " + arguments.getArgumentNames());
        } else {
            final String functionId = fnTerms.get(0).stringValue();
            try {
                return functionAgent.execute(functionId, arguments);
            } catch (InvocationTargetException e) {
                logger.error("Function '{}' failed to execute with {}", functionId, e.getTargetException().getMessage());
                return null;
            }
        }
    }
}