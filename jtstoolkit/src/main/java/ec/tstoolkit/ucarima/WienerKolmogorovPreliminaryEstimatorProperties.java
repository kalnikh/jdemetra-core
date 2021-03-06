/*
* Copyright 2013 National Bank of Belgium
*
* Licensed under the EUPL, Version 1.1 or – as soon they will be approved 
* by the European Commission - subsequent versions of the EUPL (the "Licence");
* You may not use this work except in compliance with the Licence.
* You may obtain a copy of the Licence at:
*
* http://ec.europa.eu/idabc/eupl
*
* Unless required by applicable law or agreed to in writing, software 
* distributed under the Licence is distributed on an "AS IS" basis,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the Licence for the specific language governing permissions and 
* limitations under the Licence.
*/

package ec.tstoolkit.ucarima;

import ec.tstoolkit.BaseException;
import ec.tstoolkit.arima.ArimaModel;
import ec.tstoolkit.arima.AutoCovarianceFunction;
import ec.tstoolkit.arima.CrossCovarianceFunction;
import ec.tstoolkit.arima.IArimaModel;
import ec.tstoolkit.arima.LinearModel;
import ec.tstoolkit.arima.StationaryTransformation;
import ec.tstoolkit.design.Development;
import ec.tstoolkit.maths.Complex;
import ec.tstoolkit.maths.linearfilters.BackFilter;
import ec.tstoolkit.maths.linearfilters.FiniteFilter;
import ec.tstoolkit.maths.linearfilters.RationalFilter;
import ec.tstoolkit.maths.linearfilters.RationalForeFilter;

/**
 * The properties of the preliminary estimators are computed implicitly
 * We have: (t<=T)
 * s(t) = s(t|T) + rev(t|T)
 * sum(psim(i)a(t-i)) + sum(psip(j)a(t+j)) (final estimator)
 *  = sum(psim(i)a(t-i)) + sum(psip(j)a(t+j), j<=T-t) (preliminary estimator)
 *  + sum(psip(j)a(t+j), j>T-t) (revisions)
 * The preliminary estimators and the corresponding revisions are orthogonal
 * so that their (stationary) acgf, weights... can be computed independently
 * @author Jean Palate
 */
@Development(status = Development.Status.Alpha)
public class WienerKolmogorovPreliminaryEstimatorProperties {

    private WienerKolmogorovEstimators m_wk;
    private int m_cmp, m_lag;
    private boolean m_signal = true;
    /**
     * m_wcmp is the WK filter of the final estimator, m_wrev is the WK filter of
     * the revisions
     */
    private RationalFilter m_wcmp, m_wrev;
    private LinearModel m_stcmp, m_strev;
    private AutoCovarianceFunction m_acgfcmp, m_acgfrev;
    private CrossCovarianceFunction m_ccgf;
    private BackFilter m_ur;

    public WienerKolmogorovPreliminaryEstimatorProperties() {
    }

    public WienerKolmogorovPreliminaryEstimatorProperties(WienerKolmogorovEstimators wk) {
        m_wk = wk;
    }

    public WienerKolmogorovEstimators getWienerKolmogorovEstimators() {
        return m_wk;
    }

    public void setWienerKolmogorovEstimators(WienerKolmogorovEstimators wk) {
        m_wk = wk;
        clear();
    }

    public UcarimaModel getModel() {
        return m_wk.getUcarimaModel();
    }

    public int getSelectedComponent() {
        return m_cmp;
    }

    public boolean isSignalSelected() {
        return m_signal;
    }

    public int getLag() {
        return m_lag;
    }

    public void setLag(int lag) {
        if (m_lag != lag) {
            m_lag = lag;
            pclear();
        }
    }

    public boolean isValid() {
        return init();
    }

    public void select(int cmp, boolean signal) {
        if (cmp != m_cmp || signal != m_signal) {
            m_cmp = cmp;
            m_signal = signal;

            clear();
        }
    }

    public double getWeight(int idx) {
        if (!init()) {
            return 0;
        }
        if (m_signal) {
            return m_wcmp.getWeight(idx) - m_wrev.getWeight(idx - m_lag - 1);
        } else {
            return m_wcmp.getWeight(idx) + m_wrev.getWeight(idx - m_lag - 1);
        }
    }

    public double getStationaryAutoCovariance(int order) {
        if (!init()) {
            return 0;
        }

        int lp1 = m_lag + 1;
        double ac = m_acgfcmp.get(order) + m_acgfrev.get(order);
        if (m_signal) {
            ac -= m_ccgf.get(order - lp1) + m_ccgf.get(-order - lp1);
        } else {
            ac += m_ccgf.get(order - lp1) + m_ccgf.get(-order - lp1);
        }
        return ac;
    }

    public Complex getFrequencyResponse(double frequency) {
        if (!init()) {
            return Complex.cart(0, 0);
        }
        double lw = frequency * (1 + m_lag);
        Complex cfr = Complex.cart(Math.cos(lw), Math.sin(lw));
        if (!m_signal) {
            cfr = cfr.negate();
        }
        return m_wcmp.frequencyResponse(frequency).minus(cfr.times(m_wrev.frequencyResponse(frequency)));
    }

    public void prepare(int from, int to) {
        if (init()) {
            m_wcmp.prepare(from, to);
            m_wrev.prepare(from - m_lag - 1, to - m_lag - 1);
        }
    }

    public BackFilter getDifferencingFilter() {
        if (!init()) {
            return null;
        }
        return m_ur;
    }

    public CrossCovarianceFunction getCcgf() {

        if (!init()) {
            return null;
        }
        return m_ccgf;
    }

    public AutoCovarianceFunction getStationaryAcgf() {

        if (!init()) {
            return null;
        }
        return m_acgfcmp;
    }

    public AutoCovarianceFunction getRevisionAcgf() {
        if (!init()) {
            return null;
        }
        return m_acgfrev;
    }

    private void clear() {
        m_wcmp = null;
        m_stcmp = null;
        m_acgfcmp = null;
        m_ur = null;
        pclear();
    }

    private void pclear() {
        m_wrev = null;
        m_strev = null;
        m_acgfrev = null;
        m_ccgf = null;
    }

    private boolean init() {
        if (m_wk == null) {
            return false;
        }
        if (m_ccgf != null) {
            return true;
        }
        try {
            IArimaModel model = m_wk.getUcarimaModel().getModel();
            WienerKolmogorovEstimator fest = m_wk.finalEstimator(m_cmp, m_signal);
            if (m_wcmp == null) {
                m_wcmp = fest.getFilter();
                // ACGF = ACGF (e sta) - ACGF (st rev model) +/- 2 * Filter(e sta) * Filter(st rev model)* F ^ lag+1
                StationaryTransformation st = m_wk.finalStationaryEstimator(m_cmp, m_signal);
                m_stcmp = (LinearModel) st.stationaryModel;
                m_ur = st.unitRoots;
                m_acgfcmp = m_stcmp.getAutoCovarianceFunction();
            }
            if (m_wrev == null) {
                LinearModel errmodel = m_wk.revisionModel(m_cmp, m_lag);
                RationalForeFilter rferr = ((RationalFilter) errmodel.getFilter()).getRationalForeFilter();
                m_wrev = new RationalFilter(FiniteFilter.multiply(rferr.getNumerator(), model.getAR())/**Math.Sqrt(errmodel.InnovationVariance)*/
                        , model.getMA(), rferr.getDenominator());
                // compute ErrModel*UR
                RationalFilter ferr = new RationalFilter(FiniteFilter.multiply(m_ur, rferr.getNumerator()), BackFilter.ONE, rferr.getDenominator());

                m_strev = new LinearModel(ferr, errmodel.getInnovationVariance());
                m_acgfrev = m_strev.getAutoCovarianceFunction();
                // CCGF(A,B)[i]=CCGF(B,A)[-i]
                m_ccgf = new CrossCovarianceFunction(m_strev, m_stcmp);
            }
            return true;
        } catch (BaseException err) {
            return false;
        }
    }

}
