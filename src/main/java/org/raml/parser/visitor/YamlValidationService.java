package org.raml.parser.visitor;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.raml.parser.loader.DefaultResourceLoader;
import org.raml.parser.loader.ResourceLoader;
import org.raml.parser.rule.ValidationResult;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;

public class YamlValidationService
{

    private List<ValidationResult> errorMessage;
    private YamlValidator[] yamlValidators;
    private ResourceLoader resourceLoader;
    private NodeHandler nodeHandler;

    public YamlValidationService(YamlValidator... yamlValidators)
    {
        this(new DefaultResourceLoader(), yamlValidators);
    }

    public YamlValidationService(ResourceLoader resourceLoader, YamlValidator... yamlValidators)
    {
        this.resourceLoader = resourceLoader;
        this.yamlValidators = yamlValidators;
        this.errorMessage = new ArrayList<ValidationResult>();
        this.nodeHandler = new CompositeHandler(yamlValidators);
    }

    protected ResourceLoader getResourceLoader()
    {
        return resourceLoader;
    }

    protected NodeHandler getNodeHandler()
    {
        return nodeHandler;
    }

    public List<ValidationResult> validate(String content)
    {
        Yaml yamlParser = new Yaml();

        try
        {
            NodeVisitor nodeVisitor = new NodeVisitor(nodeHandler, resourceLoader);
            Node root = yamlParser.compose(new StringReader(content));
            preValidation((MappingNode) root);
            if (root.getNodeId() == NodeId.mapping)
            {
                nodeVisitor.visitDocument((MappingNode) root);
            }
            else
            {
                //   errorMessage.add(ValidationResult.createErrorResult(EMPTY_DOCUMENT_MESSAGE));
            }

        }
        catch (YAMLException ex)
        {
            // errorMessage.add(ValidationResult.createErrorResult(ex.getMessage()));
        }

        for (YamlValidator yamlValidator : yamlValidators)
        {
            errorMessage.addAll(yamlValidator.getMessages());
        }
        return errorMessage;
    }

    protected void preValidation(MappingNode root)
    {
        //template method
    }

}