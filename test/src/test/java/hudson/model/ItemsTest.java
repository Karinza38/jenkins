/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.model;

import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.WebResponse;
import hudson.AbortException;
import hudson.cli.CLICommand;
import hudson.cli.CLICommandInvoker;
import hudson.cli.CopyJobCommand;
import hudson.cli.CreateJobCommand;
import hudson.security.ACL;
import hudson.security.csrf.CrumbIssuer;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.httpclient.HttpStatus;

import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;

public class ItemsTest {

    @Rule public JenkinsRule r = new JenkinsRule();
    @Rule public TemporaryFolder tmpRule = new TemporaryFolder();

    @Test public void getAllItems() throws Exception {
        MockFolder d = r.createFolder("d");
        MockFolder sub2 = d.createProject(MockFolder.class, "sub2");
        MockFolder sub2a = sub2.createProject(MockFolder.class, "a");
        MockFolder sub2c = sub2.createProject(MockFolder.class, "c");
        MockFolder sub2b = sub2.createProject(MockFolder.class, "b");
        MockFolder sub1 = d.createProject(MockFolder.class, "sub1");
        FreeStyleProject root = r.createFreeStyleProject("root");
        FreeStyleProject dp = d.createProject(FreeStyleProject.class, "p");
        FreeStyleProject sub1q = sub1.createProject(FreeStyleProject.class, "q");
        FreeStyleProject sub1p = sub1.createProject(FreeStyleProject.class, "p");
        FreeStyleProject sub2ap = sub2a.createProject(FreeStyleProject.class, "p");
        FreeStyleProject sub2bp = sub2b.createProject(FreeStyleProject.class, "p");
        FreeStyleProject sub2cp = sub2c.createProject(FreeStyleProject.class, "p");
        FreeStyleProject sub2alpha = sub2.createProject(FreeStyleProject.class, "alpha");
        FreeStyleProject sub2BRAVO = sub2.createProject(FreeStyleProject.class, "BRAVO");
        FreeStyleProject sub2charlie = sub2.createProject(FreeStyleProject.class, "charlie");
        assertEquals(Arrays.asList(dp, sub1p, sub1q, sub2ap, sub2alpha, sub2bp, sub2BRAVO, sub2cp, sub2charlie), Items.getAllItems(d, FreeStyleProject.class));
        assertEquals(Arrays.<Item>asList(sub2a, sub2ap, sub2alpha, sub2b, sub2bp, sub2BRAVO, sub2c, sub2cp, sub2charlie), Items.getAllItems(sub2, Item.class));
    }

    @Issue("JENKINS-24825")
    @Test public void moveItem() throws Exception {
        File tmp = tmpRule.getRoot();
        r.jenkins.setRawBuildsDir(tmp.getAbsolutePath()+"/${ITEM_FULL_NAME}");
        MockFolder foo = r.createFolder("foo");
        MockFolder bar = r.createFolder("bar");
        FreeStyleProject test = foo.createProject(FreeStyleProject.class, "test");
        test.scheduleBuild2(0).get();
        Items.move(test, bar);
        assertFalse(new File(tmp, "foo/test/1").exists());
        assertTrue(new File(tmp, "bar/test/1").exists());
    }
    
    // TODO would be more efficient to run these all as a single test case, but after a few Jetty seems to stop serving new content and new requests just hang.

    private void overwriteTargetSetUp() throws Exception {
        // A fully visible item:
        r.createFreeStyleProject("visible").setDescription("visible");
        // An item known to exist but not visible:
        r.createFreeStyleProject("known").setDescription("known");
        // An item not even known to exist:
        r.createFreeStyleProject("secret").setDescription("secret");
        // A folder from which to launch move attacks:
        r.createFolder("d");
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().
            grant(Jenkins.READ).everywhere().to("attacker").
            grant(Item.READ, Item.CONFIGURE, Item.CREATE, Item.DELETE).onPaths("(?!known|secret).*").to("attacker").
            grant(Item.DISCOVER).onPaths("known").to("attacker"));
    }

    /** Control cases: if there is no such item yet, nothing is stopping you. */
    @Test public void overwriteNonexistentTarget() throws Exception {
        overwriteTargetSetUp();
        for (OverwriteTactic tactic : OverwriteTactic.values()) {
            tactic.run(r, "nonexistent");
            System.out.println(tactic + " worked as expected on a nonexistent target");
            r.jenkins.getItem("nonexistent").delete();
        }
    }

    private void cannotOverwrite(String target) throws Exception {
        overwriteTargetSetUp();
        for (OverwriteTactic tactic : OverwriteTactic.values()) {
            try {
                tactic.run(r, target);
                fail(tactic + " was not supposed to work against " + target);
            } catch (Exception x) {
                System.out.println("good, " + tactic + " failed on " + target + ": " + x);
                assertEquals(tactic + " still overwrote " + target, target, r.jenkins.getItemByFullName(target, FreeStyleProject.class).getDescription());
            }
        }
    }

    /** More control cases: for non-security-sensitive scenarios, we prevent you from overwriting existing items. */
    @Test public void overwriteVisibleTarget() throws Exception {
        cannotOverwrite("visible");
    }

    /** You may not overwrite an item you know is there even if you cannot see it. */
    @Test public void overwriteKnownTarget() throws Exception {
        cannotOverwrite("known");
    }

    /** You are somehow prevented from overwriting an item even if you did not previously know it was there. */
    @Issue("SECURITY-321")
    @Test public void overwriteHiddenTarget() throws Exception {
        cannotOverwrite("secret");
    }

    /** All known means of creating an item under a new name. */
    private enum OverwriteTactic {
        /** Use the REST command to create an empty project (normally used only from the UI in the New Item dialog). */
        REST_EMPTY {
            @Override void run(JenkinsRule r, String target) throws Exception {
                JenkinsRule.WebClient wc = wc(r);
                wc.getOptions().setRedirectEnabled(false);
                wc.getOptions().setThrowExceptionOnFailingStatusCode(false); // redirect perversely counts as a failure
                WebResponse webResponse = wc.getPage(new WebRequest(createCrumbedUrl(r, wc, "createItem?name=" + target + "&mode=hudson.model.FreeStyleProject"), HttpMethod.POST)).getWebResponse();
                if (webResponse.getStatusCode() != HttpStatus.SC_MOVED_TEMPORARILY) {
                    throw new FailingHttpStatusCodeException(webResponse);
                }
            }
        },
        /** Use the REST command to copy an existing project (normally used from the UI in the New Item dialog). */
        REST_COPY {
            @Override void run(JenkinsRule r, String target) throws Exception {
                r.createFreeStyleProject("dupe");
                JenkinsRule.WebClient wc = wc(r);
                wc.getOptions().setRedirectEnabled(false);
                wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
                WebResponse webResponse = wc.getPage(new WebRequest(createCrumbedUrl(r, wc, "createItem?name=" + target + "&mode=copy&from=dupe"), HttpMethod.POST)).getWebResponse();
                r.jenkins.getItem("dupe").delete();
                if (webResponse.getStatusCode() != HttpStatus.SC_MOVED_TEMPORARILY) {
                    throw new FailingHttpStatusCodeException(webResponse);
                }
            }
        },
        /** Overwrite target using REST command to create a project from XML submission. */
        REST_CREATE {
            @Override void run(JenkinsRule r, String target) throws Exception {
                JenkinsRule.WebClient wc = wc(r);
                WebRequest req = new WebRequest(createCrumbedUrl(r, wc, "createItem?name=" + target), HttpMethod.POST);
                req.setAdditionalHeader("Content-Type", "application/xml");
                req.setRequestBody("<project/>");
                wc.getPage(req);
            }
        },
        /** Overwrite target using REST command to rename an existing project (normally used from the UI in the Configure screen). */
        REST_RENAME {
            @Override void run(JenkinsRule r, String target) throws Exception {
                r.createFreeStyleProject("dupe");
                JenkinsRule.WebClient wc = wc(r);
                wc.getOptions().setRedirectEnabled(false);
                wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
                WebResponse webResponse = wc.getPage(new WebRequest(createCrumbedUrl(r, wc, "job/dupe/doRename?newName=" + target), HttpMethod.POST)).getWebResponse();
                if (webResponse.getStatusCode() != HttpStatus.SC_MOVED_TEMPORARILY) {
                    r.jenkins.getItem("dupe").delete();
                    throw new FailingHttpStatusCodeException(webResponse);
                }
                assertNull(r.jenkins.getItem("dupe"));
            }
        },
        /** Overwrite target using the CLI {@code create-job} command. */
        CLI_CREATE {
            @Override void run(JenkinsRule r, String target) throws Exception {
                CLICommand cmd = new CreateJobCommand();
                CLICommandInvoker invoker = new CLICommandInvoker(r, cmd);
                cmd.setTransportAuth(User.get("attacker").impersonate());
                int status = invoker.withStdin(new ByteArrayInputStream("<project/>".getBytes("US-ASCII"))).invokeWithArgs(target).returnCode();
                if (status != 0) {
                    throw new AbortException("CLI command failed with status " + status);
                }
            }
        },
        /** Overwrite target using the CLI {@code copy-job} command. */
        CLI_COPY {
            @Override void run(JenkinsRule r, String target) throws Exception {
                r.createFreeStyleProject("dupe");
                CLICommand cmd = new CopyJobCommand();
                CLICommandInvoker invoker = new CLICommandInvoker(r, cmd);
                cmd.setTransportAuth(User.get("attacker").impersonate());
                int status = invoker.invokeWithArgs("dupe", target).returnCode();
                r.jenkins.getItem("dupe").delete();
                if (status != 0) {
                    throw new AbortException("CLI command failed with status " + status);
                }
            }
        },
        /** Overwrite target using a move function normally called from {@code cloudbees-folder} via a {@code move} action. */
        MOVE {
            @Override void run(JenkinsRule r, String target) throws Exception {
                try {
                    SecurityContext orig = ACL.impersonate(User.get("attacker").impersonate());
                    try {
                        Items.move(r.jenkins.getItemByFullName("d", MockFolder.class).createProject(FreeStyleProject.class, target), r.jenkins);
                    } finally {
                        SecurityContextHolder.setContext(orig);
                    }
                    assertNull(r.jenkins.getItemByFullName("d/" + target));
                } catch (Exception x) {
                    r.jenkins.getItemByFullName("d/" + target).delete();
                    throw x;
                }
            }
        };
        abstract void run(JenkinsRule r, String target) throws Exception;
        private static final JenkinsRule.WebClient wc(JenkinsRule r) throws Exception {
            return r.createWebClient().login("attacker");
        }
        // TODO replace with standard version once it is fixed to detect an existing query string
        private static URL createCrumbedUrl(JenkinsRule r, JenkinsRule.WebClient wc, String relativePath) throws IOException {
            CrumbIssuer issuer = r.jenkins.getCrumbIssuer();
            String crumbName = issuer.getDescriptor().getCrumbRequestField();
            String crumb = issuer.getCrumb(null);
            return new URL(wc.getContextPath() + relativePath + (relativePath.contains("?") ? "&" : "?") + crumbName + "=" + crumb);
        }
    }

}
