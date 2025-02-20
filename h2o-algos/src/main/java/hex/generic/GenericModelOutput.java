package hex.generic;

import hex.*;
import hex.genmodel.attributes.*;
import hex.genmodel.attributes.metrics.*;
import hex.genmodel.descriptor.ModelDescriptor;
import hex.tree.isofor.ModelMetricsAnomaly;
import water.util.Log;
import water.util.TwoDimTable;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public class GenericModelOutput extends Model.Output {

    final ModelCategory _modelCategory;
    final int _nfeatures;
    TwoDimTable _variable_importances;


    public GenericModelOutput(final ModelDescriptor modelDescriptor, final ModelAttributes modelAttributes) {
        _isSupervised = modelDescriptor.isSupervised();
        _domains = modelDescriptor.scoringDomains();
        _origDomains = modelDescriptor.scoringDomains();
        _hasOffset = modelDescriptor.offsetColumn() != null;
        _hasWeights = modelDescriptor.weightsColumn() != null;
        _hasFold = modelDescriptor.foldColumn() != null;
        _distribution = modelDescriptor.modelClassDist();
        _priorClassDist = modelDescriptor.priorClassDist();
        _names = modelDescriptor.columnNames();
        _modelCategory = modelDescriptor.getModelCategory();
        _nfeatures = modelDescriptor.nfeatures();
        _model_summary = convertTable(modelAttributes.getModelSummary());
        _cross_validation_metrics_summary = convertTable(modelAttributes.getCrossValidationMetricsSummary());
        
        if (modelAttributes != null && modelAttributes instanceof SharedTreeModelAttributes) {
            fillSharedTreeModelAttributes((SharedTreeModelAttributes) modelAttributes, modelDescriptor);
        } else {
            _variable_importances = null;
            _training_metrics = null;
        }
    }

    private void fillSharedTreeModelAttributes(final SharedTreeModelAttributes sharedTreeModelAttributes, final ModelDescriptor modelDescriptor) {
        _variable_importances = convertVariableImportances(sharedTreeModelAttributes.getVariableImportances());
        _scoring_history = convertTable(sharedTreeModelAttributes.getScoringHistory());
        convertMetrics(sharedTreeModelAttributes, modelDescriptor);
    }

    private void convertMetrics(final SharedTreeModelAttributes sharedTreeModelAttributes, final ModelDescriptor modelDescriptor) {
        // Training metrics

        if (sharedTreeModelAttributes.getTrainingMetrics() != null) {
            _training_metrics = (ModelMetrics) convertObjects(sharedTreeModelAttributes.getTrainingMetrics(), determineModelmetricsType(sharedTreeModelAttributes.getTrainingMetrics(), modelDescriptor));
        }
        if (sharedTreeModelAttributes.getValidationMetrics() != null) {
            _validation_metrics = (ModelMetrics) convertObjects(sharedTreeModelAttributes.getValidationMetrics(), determineModelmetricsType(sharedTreeModelAttributes.getValidationMetrics(), modelDescriptor));
        }
        if (sharedTreeModelAttributes.getCrossValidationMetrics() != null) {
            _cross_validation_metrics = (ModelMetrics) convertObjects(sharedTreeModelAttributes.getCrossValidationMetrics(), determineModelmetricsType(sharedTreeModelAttributes.getCrossValidationMetrics(), modelDescriptor));
        }
        
    }

    private ModelMetrics determineModelmetricsType(final MojoModelMetrics mojoMetrics, final ModelDescriptor modelDescriptor) {
        final ModelCategory modelCategory = modelDescriptor.getModelCategory();
        switch (modelCategory) {
            case Binomial:
                assert mojoMetrics instanceof MojoModelMetricsBinomial;
                final MojoModelMetricsBinomial binomial = (MojoModelMetricsBinomial) mojoMetrics;
                final AUC2 auc = AUC2.emptyAUC();
                auc._auc = binomial._auc;
                auc._pr_auc = binomial._pr_auc;
                auc._gini = binomial._gini;
                return new ModelMetricsBinomialGeneric(null, null, mojoMetrics._nobs, mojoMetrics._MSE,
                        _domains[_domains.length - 1], Double.NaN, // TODO: sigma
                        auc, binomial._logloss, convertTable(binomial._gains_lift_table),
                        new CustomMetric(mojoMetrics._custom_metric_name, mojoMetrics._custom_metric_value), binomial._mean_per_class_error,
                        convertTable(binomial._thresholds_and_metric_scores), convertTable(binomial._max_criteria_and_metric_scores),
                        convertTable(binomial._confusion_matrix));
            case Multinomial:
                assert mojoMetrics instanceof MojoModelMetricsMultinomial;
                final MojoModelMetricsMultinomial multinomial = (MojoModelMetricsMultinomial) mojoMetrics;
                return new ModelMetricsMultinomialGeneric(null, null, mojoMetrics._nobs, mojoMetrics._MSE,
                        _domains[_domains.length - 1], Double.NaN,
                        convertTable(multinomial._confusion_matrix), convertTable(multinomial._hit_ratios),
                        multinomial._logloss, new CustomMetric(mojoMetrics._custom_metric_name, mojoMetrics._custom_metric_value),
                        multinomial._mean_per_class_error);
            case Regression:
                assert mojoMetrics instanceof MojoModelMetricsRegression;
                MojoModelMetricsRegression metricsRegression = (MojoModelMetricsRegression) mojoMetrics;

                return new ModelMetricsRegression(null, null, metricsRegression._nobs, metricsRegression._MSE,
                        Double.NaN, metricsRegression._mae, metricsRegression._root_mean_squared_log_error, metricsRegression._mean_residual_deviance,
                        new CustomMetric(mojoMetrics._custom_metric_name, mojoMetrics._custom_metric_value));
            case AnomalyDetection:
                assert mojoMetrics instanceof MojoModelMetricsAnomaly;
                // There is no need to introduce new Generic alternatives to the original metric objects at the moment.
                // The total values can be simply calculated. The extra calculation time is negligible.
                MojoModelMetricsAnomaly metricsAnomaly = (MojoModelMetricsAnomaly) mojoMetrics;
                return new ModelMetricsAnomaly(null, null, new CustomMetric(mojoMetrics._custom_metric_name, mojoMetrics._custom_metric_value),
                        mojoMetrics._nobs, metricsAnomaly._mean_score * metricsAnomaly._nobs, metricsAnomaly._mean_normalized_score * metricsAnomaly._nobs,
                        metricsAnomaly._description);
            case Unknown:
            case Ordinal:
            case Clustering:
            case AutoEncoder:
            case DimReduction:
            case WordEmbedding:
            case CoxPH:
            default:
                return new ModelMetrics(null, null, mojoMetrics._nobs, mojoMetrics._MSE, mojoMetrics._description,
                        new CustomMetric(mojoMetrics._custom_metric_name, mojoMetrics._custom_metric_value));
        }
    }


    @Override
    public ModelCategory getModelCategory() {
        return _modelCategory; // Might be calculated as well, but the information in MOJO is the one to display.
    }

    @Override
    public int nfeatures() {
        return _nfeatures;
    }

    private static Object convertObjects(final Object source, final Object target) {

        final Class<?> targetClass = target.getClass();
        final Field[] targetDeclaredFields = targetClass.getFields();

        final Class<?> sourceClass = source.getClass();
        final Field[] sourceDeclaredFields = sourceClass.getFields();
        
        // Create a map for faster search afterwards
        final Map<String, Field> sourceFieldMap = new HashMap(sourceDeclaredFields.length);
        for (Field sourceField : sourceDeclaredFields) {
            sourceFieldMap.put(sourceField.getName(), sourceField);
        }

        for (int i = 0; i < targetDeclaredFields.length; i++) {
            final Field targetField = targetDeclaredFields[i];
            final String targetFieldName = targetField.getName();
            final Field sourceField = sourceFieldMap.get(targetFieldName);
            if(sourceField == null) {
                Log.debug(String.format("Field '%s' not found in the source object. Ignoring.", targetFieldName));
                continue;
            }

            final boolean targetAccessible = targetField.isAccessible();
            final boolean sourceAccessible = sourceField.isAccessible();
            try{
                targetField.setAccessible(true);
                sourceField.setAccessible(true);
                if(targetField.getType().isAssignableFrom(sourceField.getType())){
                    targetField.set(target, sourceField.get(source));
                }
            } catch (IllegalAccessException e) {
                Log.err(e);
                continue;
            } finally {
                targetField.setAccessible(targetAccessible);
                sourceField.setAccessible(sourceAccessible);
            }
            
        }


        return target;
    }

    private static TwoDimTable convertVariableImportances(final VariableImportances variableImportances) {
        if(variableImportances == null) return null;

        TwoDimTable varImps = ModelMetrics.calcVarImp(variableImportances._importances, variableImportances._variables);
        return varImps;
    }
    
    private static TwoDimTable convertTable(final Table convertedTable){
        if(convertedTable == null) return null;
        final TwoDimTable table = new TwoDimTable(convertedTable.getTableHeader(), convertedTable.getTableDescription(),
                convertedTable.getRowHeaders(), convertedTable.getColHeaders(), convertedTable.getColTypesString(),
                convertedTable.getColumnFormats(), convertedTable.getColHeaderForRowHeaders());

        for (int i = 0; i < convertedTable.columns(); i++) {
            for (int j = 0; j < convertedTable.rows(); j++) {
                table.set(j, i, convertedTable.getCell(i,j));
            }
        }

        return table;
    }
    
}
