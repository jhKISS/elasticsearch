/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.reducers;

import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.search.SearchParseException;
import org.elasticsearch.search.aggregations.AggregationExecutionException;
import org.elasticsearch.search.aggregations.InvalidAggregationPathException;
import org.elasticsearch.search.aggregations.bucket.histogram.InternalHistogram;
import org.elasticsearch.search.aggregations.metrics.InternalNumericMetricsAggregation;
import org.elasticsearch.search.aggregations.reducers.derivative.DerivativeParser;
import org.elasticsearch.search.aggregations.support.AggregationPath;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A set of static helpers to simplify working with aggregation buckets, in particular
 * providing utilities that help reducers.
 */
public class BucketHelpers {

    /**
     * A gap policy determines how "holes" in a set of buckets should be handled.  For example,
     * a date_histogram might have empty buckets due to no data existing for that time interval.
     * This can cause problems for operations like a derivative, which relies on a continuous
     * function.
     *
     * "insert_zeros": empty buckets will be filled with zeros for all metrics
     * "ignore": empty buckets will simply be ignored
     */
    public static enum GapPolicy {
        INSERT_ZEROS((byte) 0, "insert_zeros"), IGNORE((byte) 1, "ignore");

        /**
         * Parse a string GapPolicy into the byte enum
         *
         * @param context SearchContext this is taking place in
         * @param text    GapPolicy in string format (e.g. "ignore")
         * @return        GapPolicy enum
         */
        public static GapPolicy parse(SearchContext context, String text) {
            GapPolicy result = null;
            for (GapPolicy policy : values()) {
                if (policy.parseField.match(text)) {
                    if (result == null) {
                        result = policy;
                    } else {
                        throw new ElasticsearchIllegalStateException("Text can be parsed to 2 different gap policies: text=[" + text
                                + "], " + "policies=" + Arrays.asList(result, policy));
                    }
                }
            }
            if (result == null) {
                final List<String> validNames = new ArrayList<>();
                for (GapPolicy policy : values()) {
                    validNames.add(policy.getName());
                }
                throw new SearchParseException(context, "Invalid gap policy: [" + text + "], accepted values: " + validNames);
            }
            return result;
        }

        private final byte id;
        private final ParseField parseField;

        private GapPolicy(byte id, String name) {
            this.id = id;
            this.parseField = new ParseField(name);
        }

        /**
         * Serialize the GapPolicy to the output stream
         *
         * @param out
         * @throws IOException
         */
        public void writeTo(StreamOutput out) throws IOException {
            out.writeByte(id);
        }

        /**
         * Deserialize the GapPolicy from the input stream
         *
         * @param in
         * @return    GapPolicy Enum
         * @throws IOException
         */
        public static GapPolicy readFrom(StreamInput in) throws IOException {
            byte id = in.readByte();
            for (GapPolicy gapPolicy : values()) {
                if (id == gapPolicy.id) {
                    return gapPolicy;
                }
            }
            throw new IllegalStateException("Unknown GapPolicy with id [" + id + "]");
        }

        /**
         * Return the english-formatted name of the GapPolicy
         *
         * @return English representation of GapPolicy
         */
        public String getName() {
            return parseField.getPreferredName();
        }
    }

    /**
     * Given a path and a set of buckets, this method will return the value inside the agg at
     * that path.  This is used to extract values for use by reducers (e.g. a derivative might need
     * the price for each bucket).  If the bucket is empty, the configured GapPolicy is invoked to
     * resolve the missing bucket
     *
     * @param histo      A series of agg buckets in the form of a histogram
     * @param bucket     A specific bucket that a value needs to be extracted from.  This bucket should be present
     *                   in the <code>histo</code> parameter
     * @param aggPath    The path to a particular value that needs to be extracted.  This path should point to a metric
     *                   inside the <code>bucket</code>
     * @param gapPolicy  The gap policy to apply if empty buckets are found
     * @return           The value extracted from <code>bucket</code> found at <code>aggPath</code>
     */
    public static Double resolveBucketValue(InternalHistogram<? extends InternalHistogram.Bucket> histo, InternalHistogram.Bucket bucket,
                                            String aggPath, GapPolicy gapPolicy) {
        try {
            Object propertyValue = bucket.getProperty(histo.getName(), AggregationPath.parse(aggPath).getPathElementsAsStringList());
            if (propertyValue == null) {
                throw new AggregationExecutionException(DerivativeParser.BUCKETS_PATH.getPreferredName()
                        + " must reference either a number value or a single value numeric metric aggregation");
            } else {
                double value;
                if (propertyValue instanceof Number) {
                    value = ((Number) propertyValue).doubleValue();
                } else if (propertyValue instanceof InternalNumericMetricsAggregation.SingleValue) {
                    value = ((InternalNumericMetricsAggregation.SingleValue) propertyValue).value();
                } else {
                    throw new AggregationExecutionException(DerivativeParser.BUCKETS_PATH.getPreferredName()
                            + " must reference either a number value or a single value numeric metric aggregation");
                }
                if (Double.isInfinite(value) || Double.isNaN(value)) {
                    switch (gapPolicy) {
                        case INSERT_ZEROS:
                            return 0.0;
                        case IGNORE:
                        default:
                            return Double.NaN;
                    }
                } else {
                    return value;
                }
            }
        } catch (InvalidAggregationPathException e) {
            return null;
        }
    }
}